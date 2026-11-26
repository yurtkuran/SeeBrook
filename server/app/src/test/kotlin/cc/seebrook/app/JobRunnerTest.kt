package cc.seebrook.app

import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.jobs.Tables.OUTBOX_JOB
import cc.seebrook.platform.alerts.Alerts
import cc.seebrook.platform.alerts.CheckJobBacklog
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.jobs.Job
import cc.seebrook.platform.jobs.JobHandler
import cc.seebrook.platform.jobs.JobRunner
import cc.seebrook.platform.jobs.Jobs
import cc.seebrook.platform.jobs.PermanentFailure
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** The durable job outbox (ED-06, ED-35): dedupe, retry with backoff, give up, reclaim, cancel. */
class JobRunnerTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val ran = mutableListOf<Job>()

    @AfterEach
    fun close() = db.close()

    private fun enqueue(key: String, kind: String = "test.job") =
        db.transaction { Jobs.enqueue(it, kind, null, key, buildJsonObject { put("key", key) }) }

    private fun status(key: String) =
        db.dsl.select(OUTBOX_JOB.STATUS).from(OUTBOX_JOB).where(OUTBOX_JOB.DEDUPE_KEY.eq(key))
            .orderBy(OUTBOX_JOB.CREATED_AT.desc(), OUTBOX_JOB.ID.desc()).limit(1).fetchOne(OUTBOX_JOB.STATUS)

    private fun runner(handler: JobHandler = JobHandler { ran += it }, maxAttempts: Int = 3) =
        JobRunner(db, mapOf("test.job" to handler), maxAttempts)

    /** Makes every queued job due now, as if the backoff had elapsed. */
    private fun makeDue() = db.dsl.update(OUTBOX_JOB).set(OUTBOX_JOB.RUN_AFTER, DSL.currentOffsetDateTime()).execute()

    @Test
    fun `a job runs once and only one open job may exist per key`() {
        enqueue("a") shouldBe true
        enqueue("a") shouldBe false

        runner().drain() shouldBe 1
        ran.single().payload["key"]?.jsonPrimitive?.content shouldBe "a"
        ran.single().attempt shouldBe 1
        status("a") shouldBe "done"
        runner().drain() shouldBe 0

        // Once finished, the same key may be queued again (e.g. a later resend).
        enqueue("a") shouldBe true
    }

    @Test
    fun `a failing job is retried later, then marked failed with only the error type recorded`() {
        enqueue("b")
        val failing = runner(JobHandler { throw IllegalStateException("secret owner@example.test") })

        failing.drain() shouldBe 1
        status("b") shouldBe "queued"
        failing.drain() shouldBe 0 // backing off

        makeDue(); failing.drain()
        makeDue(); failing.drain()
        status("b") shouldBe "failed"
        val job = db.dsl.selectFrom(OUTBOX_JOB).where(OUTBOX_JOB.KIND.eq("test.job")).fetchSingle()
        job.attempts shouldBe 3
        job.lastError shouldBe "IllegalStateException"
        makeDue(); failing.drain() shouldBe 0
    }

    @Test
    fun `backoff grows to an hour`() {
        (1..5).map { JobRunner.backoff(it) } shouldBe
            listOf(Duration.ofMinutes(1), Duration.ofMinutes(4), Duration.ofMinutes(16), Duration.ofMinutes(60), Duration.ofMinutes(60))
    }

    @Test
    fun `kinds without a handler stay queued`() {
        enqueue("c", kind = "email.unconfigured")
        runner().drain() shouldBe 0
        JobRunner(db, emptyMap()).drain() shouldBe 0
        status("c") shouldBe "queued"
    }

    @Test
    fun `a job left running by a dead worker is claimed again after its lease`() {
        enqueue("d")
        db.dsl.update(OUTBOX_JOB)
            .set(OUTBOX_JOB.STATUS, "running")
            .set(OUTBOX_JOB.ATTEMPTS, 1)
            .set(OUTBOX_JOB.LOCKED_UNTIL, DSL.currentOffsetDateTime().plus(1))
            .execute()
        runner().drain() shouldBe 0 // still leased

        db.dsl.update(OUTBOX_JOB).set(OUTBOX_JOB.LOCKED_UNTIL, DSL.currentOffsetDateTime().minus(1)).execute()
        runner().drain() shouldBe 1
        ran.single().attempt shouldBe 2
        status("d") shouldBe "done"
    }

    @Test
    fun `a cancelled job never runs`() {
        enqueue("e")
        db.transaction { Jobs.cancelQueued(it, "e") } shouldBe true
        runner().drain() shouldBe 0
        status("e") shouldBe "cancelled"
        enqueue("e") shouldBe true
    }

    @Test
    fun `a permanent failure is not retried`() {
        enqueue("f")
        class Rejected : RuntimeException(), PermanentFailure
        runner(JobHandler { throw Rejected() }).drain() shouldBe 1

        status("f") shouldBe "failed"
        db.dsl.selectFrom(OUTBOX_JOB).where(OUTBOX_JOB.KIND.eq("test.job")).fetchSingle().attempts shouldBe 1
    }

    private fun openAlerts() = db.transaction { Alerts.list(it, includeResolved = false) }

    @Test
    fun `a job that gives up opens an operator alert with its kind and error type only`() {
        enqueue("g")
        runner(JobHandler { throw IllegalStateException("owner@example.test") }, maxAttempts = 1).drain()

        val alert = openAlerts().single()
        alert.kind shouldBe "job_failed"
        alert.summary shouldBe "Background job test.job gave up after 1 attempt(s)"
        alert.details.toString() shouldContain "IllegalStateException"
        alert.details.toString() shouldNotContain "example.test"
        db.dsl.fetchCount(OUTBOX_JOB, OUTBOX_JOB.KIND.eq(Alerts.EMAIL_KIND)) shouldBe 1
    }

    @Test
    fun `an alert email that gives up is only logged, never alerted again`() {
        db.transaction { Alerts.raise(it, "test", "test:1", null, "Something happened") } shouldBe true
        JobRunner(db, mapOf(Alerts.EMAIL_KIND to JobHandler { throw IllegalStateException() }), maxAttempts = 1).drain() shouldBe 1
        openAlerts().map { it.key } shouldBe listOf("test:1")
        db.dsl.fetchCount(OUTBOX_JOB, OUTBOX_JOB.KIND.eq(Alerts.EMAIL_KIND)) shouldBe 1
    }

    @Test
    fun `jobs waiting too long open a backlog alert that clears once they run`() {
        enqueue("h", kind = "email.unconfigured")
        CheckJobBacklog(db)() shouldBe 0 // just queued
        db.dsl.update(OUTBOX_JOB).set(OUTBOX_JOB.RUN_AFTER, DSL.currentOffsetDateTime().minus(1)).execute()
        CheckJobBacklog(db)() shouldBe 1
        openAlerts().single().details.toString() shouldContain "email.unconfigured"

        db.transaction { Jobs.cancelQueued(it, "h") }
        CheckJobBacklog(db)() shouldBe 0
        openAlerts() shouldBe emptyList()
    }
}
