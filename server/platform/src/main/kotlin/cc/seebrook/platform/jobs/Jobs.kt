package cc.seebrook.platform.jobs

import cc.seebrook.db.generated.jobs.Tables.OUTBOX_JOB
import cc.seebrook.platform.db.Database
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSON
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object JobStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/** A claimed job. [attempt] counts from 1. */
data class Job(val id: UUID, val kind: String, val hoaId: UUID?, val payload: JsonObject, val attempt: Int)

/**
 * Marks an exception as permanent: retrying cannot help (e.g. the provider rejected the address),
 * so the job is marked failed at once instead of being retried.
 */
interface PermanentFailure

/** Runs one kind of job. Return normally when done (or nothing is left to do); throw to retry later. */
fun interface JobHandler {
    fun handle(job: Job)
}

/**
 * The durable outbox (ED-06, ED-35). Services enqueue inside their own transaction, so a job exists
 * only if the change that needed it committed.
 */
object Jobs {
    /** Queues a job unless one with [dedupeKey] is already queued or running. True if queued. */
    fun enqueue(tx: DSLContext, kind: String, hoaId: UUID?, dedupeKey: String, payload: JsonObject): Boolean =
        tx.insertInto(OUTBOX_JOB)
            .set(OUTBOX_JOB.KIND, kind)
            .set(OUTBOX_JOB.HOA_ID, hoaId)
            .set(OUTBOX_JOB.DEDUPE_KEY, dedupeKey)
            .set(OUTBOX_JOB.PAYLOAD, JSON.valueOf(payload.toString()))
            .onConflictDoNothing()
            .execute() == 1

    /** Cancels the queued job with [dedupeKey], if any. A running job finishes; handlers re-check state. */
    fun cancelQueued(tx: DSLContext, dedupeKey: String): Boolean =
        tx.update(OUTBOX_JOB)
            .set(OUTBOX_JOB.STATUS, JobStatus.CANCELLED)
            .set(OUTBOX_JOB.FINISHED_AT, DSL.currentOffsetDateTime())
            .where(OUTBOX_JOB.DEDUPE_KEY.eq(dedupeKey))
            .and(OUTBOX_JOB.STATUS.eq(JobStatus.QUEUED))
            .execute() == 1

    /** Which of [dedupeKeys] have a queued or running job. */
    fun openKeys(tx: DSLContext, dedupeKeys: Collection<String>): Set<String> =
        if (dedupeKeys.isEmpty()) {
            emptySet()
        } else {
            tx.select(OUTBOX_JOB.DEDUPE_KEY).from(OUTBOX_JOB)
                .where(OUTBOX_JOB.DEDUPE_KEY.`in`(dedupeKeys))
                .and(OUTBOX_JOB.STATUS.`in`(JobStatus.QUEUED, JobStatus.RUNNING))
                .fetchSet(OUTBOX_JOB.DEDUPE_KEY)
        }
}

/**
 * Claims and runs due jobs one at a time (`FOR UPDATE SKIP LOCKED`, so several workers never run
 * the same job). A failure is retried with backoff, then marked failed after [maxAttempts].
 * Only kinds with a handler are claimed; others stay queued (e.g. email while no provider is set).
 */
class JobRunner(
    private val db: Database,
    private val handlers: Map<String, JobHandler>,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val lease: Duration = Duration.ofMinutes(5),
) {
    private val log = LoggerFactory.getLogger(JobRunner::class.java)

    val kinds: Set<String> get() = handlers.keys

    /** Runs the next due job. False when none is due. */
    fun runNext(): Boolean {
        if (handlers.isEmpty()) return false
        val job = db.transaction(::claim) ?: return false
        try {
            handlers.getValue(job.kind).handle(job)
            db.transaction { finish(it, job.id) }
        } catch (e: Exception) {
            val failed = db.transaction { retryOrFail(it, job, e, permanent = e is PermanentFailure) }
            // Type only: exception messages can contain personal data.
            log.warn(
                "Job {} ({}) attempt {} failed with {}; {}",
                job.id, job.kind, job.attempt, e.javaClass.simpleName, if (failed) "giving up" else "will retry",
            )
        }
        return true
    }

    /** Runs due jobs until none is left or [max] have run. Returns how many ran. */
    fun drain(max: Int = 100): Int {
        var count = 0
        while (count < max && runNext()) count++
        return count
    }

    private fun claim(tx: DSLContext): Job? {
        val now = DSL.currentOffsetDateTime()
        val record = tx.selectFrom(OUTBOX_JOB)
            .where(OUTBOX_JOB.KIND.`in`(handlers.keys))
            .and(
                OUTBOX_JOB.STATUS.eq(JobStatus.QUEUED).and(OUTBOX_JOB.RUN_AFTER.le(now))
                    .or(OUTBOX_JOB.STATUS.eq(JobStatus.RUNNING).and(OUTBOX_JOB.LOCKED_UNTIL.lt(now))),
            )
            .orderBy(OUTBOX_JOB.RUN_AFTER, OUTBOX_JOB.ID)
            .limit(1)
            .forUpdate()
            .skipLocked()
            .fetchOne() ?: return null
        val attempt = record.attempts + 1
        tx.update(OUTBOX_JOB)
            .set(OUTBOX_JOB.STATUS, JobStatus.RUNNING)
            .set(OUTBOX_JOB.ATTEMPTS, attempt)
            .set(OUTBOX_JOB.LOCKED_UNTIL, nowPlus(lease))
            .where(OUTBOX_JOB.ID.eq(record.id))
            .execute()
        return Job(
            requireNotNull(record.id),
            record.kind,
            record.hoaId,
            Json.parseToJsonElement(record.payload.data()).jsonObject,
            attempt,
        )
    }

    private fun finish(tx: DSLContext, id: UUID) {
        // A job cancelled while running stays cancelled.
        tx.update(OUTBOX_JOB)
            .set(OUTBOX_JOB.STATUS, JobStatus.DONE)
            .set(OUTBOX_JOB.LOCKED_UNTIL, null as OffsetDateTime?)
            .set(OUTBOX_JOB.FINISHED_AT, DSL.currentOffsetDateTime())
            .where(OUTBOX_JOB.ID.eq(id)).and(OUTBOX_JOB.STATUS.eq(JobStatus.RUNNING))
            .execute()
    }

    /** True if the job has now failed for good. */
    private fun retryOrFail(tx: DSLContext, job: Job, error: Exception, permanent: Boolean): Boolean {
        val giveUp = permanent || job.attempt >= maxAttempts
        val update = tx.update(OUTBOX_JOB)
            .set(OUTBOX_JOB.LOCKED_UNTIL, null as OffsetDateTime?)
            .set(OUTBOX_JOB.LAST_ERROR, error.javaClass.simpleName.take(200))
        val next = if (giveUp) {
            update.set(OUTBOX_JOB.STATUS, JobStatus.FAILED).set(OUTBOX_JOB.FINISHED_AT, DSL.currentOffsetDateTime())
        } else {
            update.set(OUTBOX_JOB.STATUS, JobStatus.QUEUED)
                .set(OUTBOX_JOB.RUN_AFTER, nowPlus(backoff(job.attempt)))
        }
        next.where(OUTBOX_JOB.ID.eq(job.id)).and(OUTBOX_JOB.STATUS.eq(JobStatus.RUNNING)).execute()
        return giveUp
    }

    /** Database time, so every worker agrees regardless of its own clock. */
    private fun nowPlus(duration: Duration): Field<OffsetDateTime> =
        DSL.field("now() + make_interval(secs => {0})", OffsetDateTime::class.java, DSL.inline(duration.toSeconds().toDouble()))

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 6

        /** 1, 4, 16 minutes, then hourly. */
        fun backoff(attempt: Int): Duration =
            Duration.ofMinutes(minOf(60L, 1L shl (2 * (attempt - 1).coerceIn(0, 3))))
    }
}

/** Runs [runner] on one background thread every [interval] while the server is up (ED-06). */
class JobWorker(private val runner: JobRunner, private val interval: Duration = Duration.ofSeconds(10)) : AutoCloseable {
    private val log = LoggerFactory.getLogger(JobWorker::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "seebrook-jobs").apply { isDaemon = true } }

    fun start() {
        executor.scheduleWithFixedDelay(
            {
                try {
                    runner.drain()
                } catch (e: Exception) {
                    log.error("Job worker pass failed with {}", e.javaClass.simpleName)
                }
            },
            0, interval.toMillis(), TimeUnit.MILLISECONDS,
        )
    }

    override fun close() {
        executor.shutdown()
        executor.awaitTermination(30, TimeUnit.SECONDS)
    }
}
