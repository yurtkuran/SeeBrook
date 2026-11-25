package cc.seebrook.finance

import cc.seebrook.db.generated.finance.Tables.RECURRING_PLAN
import cc.seebrook.db.generated.finance.Tables.SAVED_PAYMENT_METHOD
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.email.EmailGateway
import cc.seebrook.platform.email.Recipient
import cc.seebrook.platform.email.RenderedEmail
import cc.seebrook.platform.jobs.Job
import cc.seebrook.platform.jobs.JobHandler
import cc.seebrook.platform.jobs.Jobs
import cc.seebrook.property.Properties
import cc.seebrook.tenant.Hoas
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Recurring plan emails to the plan's owner (ED-59): set up or changed, canceled, waiting for the
 * owner's approval, or a due date skipped. Charges themselves send the usual payment emails
 * (receipt, failure). Queued in the same transaction as the change (ED-35).
 */
object RecurringEmailJob {
    const val KIND = "finance.recurring_email"

    enum class Notice(val code: String) {
        SET_UP("set_up"),
        CANCELED("canceled"),
        NEEDS_APPROVAL("needs_approval"),
        SKIPPED("skipped"),
        ;

        companion object {
            fun fromCode(code: String) = entries.first { it.code == code }
        }
    }

    internal fun enqueue(tx: DSLContext, hoaId: UUID, planId: UUID, notice: Notice, at: OffsetDateTime, dueDate: LocalDate? = null): Boolean =
        Jobs.enqueue(
            tx, KIND, hoaId, "recurring-email:$planId:${notice.code}:${dueDate ?: at.toInstant().toEpochMilli()}",
            buildJsonObject {
                put("planId", planId.toString())
                put("notice", notice.code)
                dueDate?.let { put("dueDate", it.toString()) }
            },
        )
}

/** Job handler: renders and sends one recurring plan email from the plan as it is now. */
class SendRecurringEmail(
    private val db: Database,
    private val email: EmailGateway,
    private val contacts: PayerContacts,
) : JobHandler {
    override fun handle(job: Job) {
        val planId = UUID.fromString(requireNotNull(job.payload["planId"]).jsonPrimitive.content)
        val notice = RecurringEmailJob.Notice.fromCode(requireNotNull(job.payload["notice"]).jsonPrimitive.content)
        val dueDate = job.payload["dueDate"]?.jsonPrimitive?.content?.let(LocalDate::parse)
        val (userId, view) = db.transaction { tx ->
            val plan = tx.selectFrom(RECURRING_PLAN).where(RECURRING_PLAN.ID.eq(planId)).fetchOne() ?: return@transaction null
            val hoa = requireNotNull(Hoas.find(tx, plan.hoaId))
            val unit = Properties.unitLabel(tx, plan.hoaId, plan.propertyId) ?: return@transaction null
            val method = tx.selectFrom(SAVED_PAYMENT_METHOD).where(SAVED_PAYMENT_METHOD.ID.eq(plan.savedMethodId)).fetchSingle()
            plan.userId to RecurringEmail.View(
                hoaName = hoa.name,
                unit = unit,
                frequency = requireNotNull(Frequency.fromCode(plan.frequency)),
                duesCents = plan.duesCents,
                feeCents = plan.feeCents,
                totalCents = plan.totalCents,
                methodLabel = method.label,
                reason = plan.statusReason,
                dueDate = dueDate,
                nextChargeDate = plan.nextDate?.let(BankCalendar::chargeDay),
            )
        } ?: return
        val recipient = contacts.find(userId) ?: return
        email.sendTransactional(recipient, RecurringEmail.compose(recipient, notice, view))
    }
}

/** Recurring plan email templates: plain text, amounts and the method's label only. */
internal object RecurringEmail {
    data class View(
        val hoaName: String,
        val unit: String,
        val frequency: Frequency,
        val duesCents: Long,
        val feeCents: Long,
        val totalCents: Long,
        val methodLabel: String,
        val reason: String?,
        val dueDate: LocalDate?,
        val nextChargeDate: LocalDate?,
    )

    private val date = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)

    fun compose(to: Recipient, notice: RecurringEmailJob.Notice, v: View): RenderedEmail {
        val hello = to.name?.let { "Hello $it," } ?: "Hello,"
        val every = when (v.frequency) {
            Frequency.MONTHLY -> "every month"
            Frequency.QUARTERLY -> "every quarter"
            Frequency.ANNUAL -> "every year"
        }
        val amounts = buildString {
            append("  HOA dues:      ${Money.format(v.duesCents)}\n")
            if (v.feeCents > 0) append("  Fee:           ${Money.format(v.feeCents)}\n")
            append("  Each charge:   ${Money.format(v.totalCents)} (${v.methodLabel})")
        }
        val next = v.nextChargeDate?.let { "The next charge is on ${date.format(it)}." }.orEmpty()
        val footer = "You can change, pause or cancel it any time in the Seebrook app. Questions? Contact your HOA board."
        return when (notice) {
            RecurringEmailJob.Notice.SET_UP -> RenderedEmail(
                subject = "Recurring payment set up for ${v.unit}",
                text = """
                    |$hello
                    |
                    |Your recurring payment to ${v.hoaName} for ${v.unit} is set up. It charges the same amount $every:
                    |
                    |$amounts
                    |
                    |$next Each payment gets its own receipt.
                    |
                    |$footer
                """.trimMargin(),
            )
            RecurringEmailJob.Notice.CANCELED -> RenderedEmail(
                subject = "Recurring payment canceled for ${v.unit}",
                text = """
                    |$hello
                    |
                    |${
                    if (v.reason == "ownership_ended") {
                        "Your recurring payment to ${v.hoaName} for ${v.unit} was canceled because you are no longer listed as an owner of ${v.unit}."
                    } else {
                        "Your recurring payment to ${v.hoaName} for ${v.unit} was canceled, as you asked."
                    }
                    } No further charges will be made. Payments already submitted still complete.
                    |
                    |Questions? Contact your HOA board.
                """.trimMargin(),
            )
            RecurringEmailJob.Notice.NEEDS_APPROVAL -> RenderedEmail(
                subject = "Action needed: your recurring payment for ${v.unit}",
                text = """
                    |$hello
                    |
                    |${
                    if (v.reason == "fee_changed") {
                        "${v.hoaName} changed the fee for ${v.methodLabel}, so we did not charge your recurring payment${v.dueDate?.let { " due ${date.format(it)}" }.orEmpty()}. We never charge more than you approved."
                    } else {
                        "${v.methodLabel} can no longer be used for payments to ${v.hoaName}, so we did not charge your recurring payment${v.dueDate?.let { " due ${date.format(it)}" }.orEmpty()}."
                    }
                    }
                    |Nothing was charged. Open the Seebrook app to review and approve your recurring payment again; until then it stays on hold.
                    |You can also pay this amount in the app now.
                """.trimMargin(),
            )
            RecurringEmailJob.Notice.SKIPPED -> RenderedEmail(
                subject = "Your recurring payment for ${v.unit} was not charged",
                text = """
                    |$hello
                    |
                    |Online payments to ${v.hoaName} were unavailable, so your recurring payment${v.dueDate?.let { " due ${date.format(it)}" }.orEmpty()} for ${v.unit} was not charged and will not be charged later.
                    |Nothing was charged. Please pay this amount in the Seebrook app once payments are available again. $next
                    |
                    |$footer
                """.trimMargin(),
            )
        }
    }
}
