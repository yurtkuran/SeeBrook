package cc.seebrook.platform.alerts

import cc.seebrook.db.generated.jobs.Tables.OUTBOX_JOB
import cc.seebrook.db.generated.ops.Tables.ALERT
import cc.seebrook.db.generated.ops.tables.records.AlertRecord
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.email.EmailGateway
import cc.seebrook.platform.email.Recipient
import cc.seebrook.platform.email.RenderedEmail
import cc.seebrook.platform.jobs.Job
import cc.seebrook.platform.jobs.JobHandler
import cc.seebrook.platform.jobs.Jobs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.JSON
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One operator alert (ED-61). */
data class Alert(
    val id: UUID,
    val kind: String,
    val key: String,
    val hoaId: UUID?,
    val summary: String,
    val details: JsonObject,
    val occurrences: Int,
    val openedAt: OffsetDateTime,
    val lastSeenAt: OffsetDateTime,
    val resolvedAt: OffsetDateTime?,
    val resolvedBy: UUID?,
    val resolution: String?,
) {
    val open: Boolean get() = resolvedAt == null
}

/**
 * Operator alerts (ED-61): problems an operator must look at, recorded in the same transaction as
 * whatever found them and emailed to the Root operators through the outbox. One open alert per
 * key: finding the same problem again counts another occurrence instead of sending another email.
 * Problems that clear by themselves (an outage, a backlog) are resolved by the code that sees them
 * clear; others are resolved by an operator with `resolve-alert`.
 *
 * Summaries and details carry identifiers, references and counts only (never personal data or
 * secrets). Each new alert is also logged at ERROR with the `ALERT:` prefix, so a log-based pager
 * sees it even when email is not configured.
 */
object Alerts {
    const val EMAIL_KIND = "platform.alert_email"

    private val log = LoggerFactory.getLogger(Alerts::class.java)

    /** Records [key]'s problem. True when this opened a new alert (and queued its email). */
    fun raise(
        tx: DSLContext,
        kind: String,
        key: String,
        hoaId: UUID?,
        summary: String,
        details: JsonObject = JsonObject(emptyMap()),
    ): Boolean {
        fun seenAgain(): Boolean = tx.update(ALERT)
            .set(ALERT.OCCURRENCES, ALERT.OCCURRENCES.plus(1))
            .set(ALERT.LAST_SEEN_AT, DSL.currentOffsetDateTime())
            .set(ALERT.SUMMARY, summary.take(500))
            .set(ALERT.DETAILS, JSON.valueOf(details.toString()))
            .where(ALERT.ALERT_KEY.eq(key)).and(ALERT.RESOLVED_AT.isNull)
            .execute() == 1
        if (seenAgain()) return false
        val id = tx.insertInto(ALERT)
            .set(ALERT.KIND, kind)
            .set(ALERT.ALERT_KEY, key.take(200))
            .set(ALERT.HOA_ID, hoaId)
            .set(ALERT.SUMMARY, summary.take(500))
            .set(ALERT.DETAILS, JSON.valueOf(details.toString()))
            .onConflictDoNothing()
            .returning(ALERT.ID)
            .fetchOne()?.id
        if (id == null) {
            // Another transaction opened it a moment ago.
            seenAgain()
            return false
        }
        log.error("ALERT: {} ({})", summary, key)
        AlertEmailJob.enqueue(tx, id, AlertEmailJob.Event.OPENED)
        return true
    }

    /** The problem behind [key] cleared by itself. True if an open alert was resolved. */
    fun clear(tx: DSLContext, key: String, resolution: String): Boolean {
        val id = tx.update(ALERT)
            .set(ALERT.RESOLVED_AT, DSL.currentOffsetDateTime())
            .set(ALERT.RESOLUTION, resolution.take(1000))
            .where(ALERT.ALERT_KEY.eq(key)).and(ALERT.RESOLVED_AT.isNull)
            .returning(ALERT.ID)
            .fetchOne()?.id ?: return false
        log.warn("Alert cleared: {} ({})", resolution, key)
        AlertEmailJob.enqueue(tx, id, AlertEmailJob.Event.CLEARED)
        return true
    }

    /** Keys of the open alerts whose key starts with [prefix]. */
    fun openKeys(tx: DSLContext, prefix: String): Set<String> =
        tx.select(ALERT.ALERT_KEY).from(ALERT)
            .where(ALERT.ALERT_KEY.startsWith(prefix)).and(ALERT.RESOLVED_AT.isNull)
            .fetchSet(ALERT.ALERT_KEY)

    fun find(tx: DSLContext, id: UUID): Alert? = tx.selectFrom(ALERT).where(ALERT.ID.eq(id)).fetchOne()?.toAlert()

    /** Open alerts, oldest first; with [includeResolved], also the most recent resolved ones. */
    fun list(tx: DSLContext, includeResolved: Boolean, limit: Int = 200): List<Alert> =
        tx.selectFrom(ALERT)
            .where(if (includeResolved) DSL.noCondition() else ALERT.RESOLVED_AT.isNull)
            .orderBy(ALERT.RESOLVED_AT.isNull.desc(), ALERT.OPENED_AT.desc())
            .limit(limit)
            .fetch { it.toAlert() }

    private fun AlertRecord.toAlert() = Alert(
        id = requireNotNull(id),
        kind = kind,
        key = alertKey,
        hoaId = hoaId,
        summary = summary,
        details = Json.parseToJsonElement(details.data()).jsonObject,
        occurrences = occurrences,
        openedAt = openedAt,
        lastSeenAt = lastSeenAt,
        resolvedAt = resolvedAt,
        resolvedBy = resolvedBy,
        resolution = resolution,
    )
}

/**
 * An operator marks an alert as dealt with (`resolve-alert`): Root only, with a reason, audited.
 * If the problem is still there, the next check opens a new alert.
 */
class ResolveAlert(private val db: Database) {
    sealed interface Result {
        data class Resolved(val alert: Alert) : Result
        data object NotFound : Result
        data object AlreadyResolved : Result
        data object Forbidden : Result
        data object ReasonRequired : Result
    }

    operator fun invoke(actor: UserActor, alertId: UUID, reason: String?): Result {
        if (!actor.isRoot) return Result.Forbidden
        val why = reason?.trim()?.takeIf { it.isNotEmpty() } ?: return Result.ReasonRequired
        return db.transaction { tx ->
            val alert = Alerts.find(tx, alertId) ?: return@transaction Result.NotFound
            val updated = tx.update(ALERT)
                .set(ALERT.RESOLVED_AT, DSL.currentOffsetDateTime())
                .set(ALERT.RESOLVED_BY, actor.userId)
                .set(ALERT.RESOLUTION, why.take(1000))
                .where(ALERT.ID.eq(alertId)).and(ALERT.RESOLVED_AT.isNull)
                .execute()
            if (updated == 0) return@transaction Result.AlreadyResolved
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "ops.alert_resolved",
                    hoaId = alert.hoaId,
                    targetType = "alert",
                    targetId = alertId,
                    reason = why,
                    details = buildJsonObject {
                        put("kind", alert.kind)
                        put("key", alert.key)
                    },
                ),
            )
            Result.Resolved(requireNotNull(Alerts.find(tx, alertId)))
        }
    }
}

/** Emails each alert to the operators when it opens and when it clears by itself. */
object AlertEmailJob {
    enum class Event(val code: String) { OPENED("opened"), CLEARED("cleared") }

    internal fun enqueue(tx: DSLContext, alertId: UUID, event: Event) {
        Jobs.enqueue(
            tx, Alerts.EMAIL_KIND, null, "alert-email:$alertId:${event.code}",
            buildJsonObject {
                put("alertId", alertId.toString())
                put("event", event.code)
            },
        )
    }
}

/**
 * Sends one alert email to every Root operator ([operators]). With no operator to send to, the
 * alert stays in the log and in `list-alerts`.
 */
class SendAlertEmail(
    private val db: Database,
    private val email: EmailGateway,
    private val operators: () -> List<Recipient>,
    private val hoaName: (UUID) -> String?,
) : JobHandler {
    private val log = LoggerFactory.getLogger(SendAlertEmail::class.java)
    private val time = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm 'UTC'")

    override fun handle(job: Job) {
        val alertId = UUID.fromString(requireNotNull(job.payload["alertId"]).jsonPrimitive.content)
        val event = requireNotNull(job.payload["event"]).jsonPrimitive.content
        val alert = db.transaction { Alerts.find(it, alertId) } ?: return
        val recipients = operators()
        if (recipients.isEmpty()) {
            log.warn("No Root operator to email alert {} to", alertId)
            return
        }
        val hoa = alert.hoaId?.let(hoaName)
        val where = hoa?.let { " ($it)" }.orEmpty()
        val rendered = if (event == AlertEmailJob.Event.CLEARED.code) {
            RenderedEmail(
                subject = "[Seebrook] Resolved: ${alert.summary}$where".take(200),
                text = """
                    |Resolved by itself: ${alert.resolution ?: "the problem cleared"}.
                    |
                    |${alert.summary}
                    |Opened: ${time.format(alert.openedAt.withOffsetSameInstant(ZoneOffset.UTC))}
                    |Resolved: ${alert.resolvedAt?.let { time.format(it.withOffsetSameInstant(ZoneOffset.UTC)) } ?: "-"}
                    |Seen ${alert.occurrences} time(s).
                    |
                    |Alert ${alert.id}
                """.trimMargin(),
            )
        } else {
            RenderedEmail(
                subject = "[Seebrook alert] ${alert.summary}$where".take(200),
                text = """
                    |${alert.summary}
                    |
                    |HOA: ${hoa ?: "all"}
                    |Kind: ${alert.kind}
                    |Opened: ${time.format(alert.openedAt.withOffsetSameInstant(ZoneOffset.UTC))}
                    |${alert.details.entries.joinToString("\n") { (k, v) -> "$k: ${v.toString().trim('"')}" }}
                    |
                    |What to do: ${AlertGuide.forKind(alert.kind)}
                    |
                    |List open alerts:  seebrook list-alerts --operator <you>
                    |When it is handled: seebrook resolve-alert --operator <you> --alert ${alert.id} --reason "<what was done>"
                    |
                    |You get one email per problem; repeats are counted on the alert.
                """.trimMargin(),
            )
        }
        recipients.forEach { email.sendTransactional(it, rendered) }
    }
}

/** First steps per alert kind, in the email and `list-alerts`. */
object AlertGuide {
    fun forKind(kind: String): String = when (kind) {
        "payments_outage" -> "Online payments for this HOA are paused automatically and resume by themselves when the provider answers again (checked every few minutes). Check the provider's status page. Nothing to do unless it lasts."
        "payment_mismatch" -> "A provider event did not match Seebrook's payment (account, amount or state) and was not applied. Compare the payment in the provider's dashboard with `list-payments`; correct with an adjustment if needed."
        "payment_lookup_refused" -> "The provider refused to look up this payment. Check it in the provider's dashboard."
        "payments_unsettled" -> "These payments have not settled in the usual time. Check them in the provider's dashboard and that webhooks are arriving."
        "job_failed" -> "A background job gave up after its retries (for example an email the provider would not accept). Check the log for the job ID and fix the cause."
        "jobs_backlog" -> "Background jobs are waiting longer than they should: the worker may be stopped, or email may not be configured."
        else -> "Check the log around the time it opened."
    }
}

/**
 * Background jobs waiting much longer than planned (ED-61): the worker is stopped, a kind has no
 * handler (email not configured), or something keeps failing. Opens `jobs_backlog` while any due
 * job is older than [overdueAfter]; clears it once none is.
 */
class CheckJobBacklog(private val db: Database, private val overdueAfter: Duration = Duration.ofMinutes(30)) {
    operator fun invoke(): Int = db.transaction { tx ->
        val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(overdueAfter)
        val overdue = tx.select(OUTBOX_JOB.KIND, DSL.count(), DSL.min(OUTBOX_JOB.RUN_AFTER)).from(OUTBOX_JOB)
            .where(OUTBOX_JOB.STATUS.eq("queued")).and(OUTBOX_JOB.RUN_AFTER.lt(cutoff))
            .groupBy(OUTBOX_JOB.KIND)
            .fetch()
        val total = overdue.sumOf { it.value2() }
        if (total == 0) {
            Alerts.clear(tx, KEY, "no background jobs are waiting any more")
        } else {
            Alerts.raise(
                tx, "jobs_backlog", KEY, null,
                "$total background job(s) waiting more than ${overdueAfter.toMinutes()} minutes",
                buildJsonObject {
                    overdue.forEach { put("waiting ${it.value1()}", it.value2()) }
                    put("oldest due", overdue.minOf { it.value3() }.withOffsetSameInstant(ZoneOffset.UTC).toString())
                },
            )
        }
        total
    }

    companion object {
        const val KEY = "jobs_backlog"
    }
}
