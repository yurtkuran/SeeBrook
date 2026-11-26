package cc.seebrook.finance

import cc.seebrook.db.generated.finance.Tables.PAYMENT
import cc.seebrook.db.generated.finance.Tables.PAYMENT_ADJUSTMENT
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.email.EmailGateway
import cc.seebrook.platform.email.Recipient
import cc.seebrook.platform.email.RenderedEmail
import cc.seebrook.platform.jobs.Job
import cc.seebrook.platform.jobs.JobHandler
import cc.seebrook.platform.jobs.Jobs
import cc.seebrook.platform.payments.AdjustmentKind
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.property.Properties
import cc.seebrook.tenant.Hoas
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Who gets a payment's emails: the owner who paid. Looked up through IdentityService (ED-13). */
fun interface PayerContacts {
    fun find(userId: UUID): Recipient?
}

/**
 * Payment emails to the owner who paid (ED-45): a receipt when the payment is received, a notice
 * when it fails, and a notice when money is refunded or a payment is returned. Queued in the same
 * transaction as the change, so an email exists only if the change committed (ED-35).
 */
object PaymentEmailJob {
    const val KIND = "finance.payment_email"

    enum class Notice(val code: String) {
        RECEIVED("received"),
        FAILED("failed"),
        REFUNDED("refunded"),
        REFUND_FAILED("refund_failed"),
        RETURNED("returned"),
        REINSTATED("reinstated"),
        ;

        companion object {
            fun of(kind: AdjustmentKind) = when (kind) {
                AdjustmentKind.REFUND -> REFUNDED
                AdjustmentKind.REFUND_FAILED -> REFUND_FAILED
                AdjustmentKind.DISPUTE_WITHDRAWN -> RETURNED
                AdjustmentKind.DISPUTE_REINSTATED -> REINSTATED
            }

            fun fromCode(code: String) = entries.first { it.code == code }
        }
    }

    internal fun enqueue(tx: DSLContext, hoaId: UUID, paymentId: UUID, notice: Notice, adjustmentId: UUID? = null): Boolean =
        Jobs.enqueue(
            tx, KIND, hoaId, "payment-email:$paymentId:${notice.code}:${adjustmentId ?: "-"}",
            buildJsonObject {
                put("paymentId", paymentId.toString())
                put("notice", notice.code)
                adjustmentId?.let { put("adjustmentId", it.toString()) }
            },
        )
}

/** Job handler: renders and sends one payment email. Nothing to send if the payer has no address. */
class SendPaymentEmail(
    private val db: Database,
    private val email: EmailGateway,
    private val contacts: PayerContacts,
) : JobHandler {
    override fun handle(job: Job) {
        val paymentId = UUID.fromString(requireNotNull(job.payload["paymentId"]).jsonPrimitive.content)
        val notice = PaymentEmailJob.Notice.fromCode(requireNotNull(job.payload["notice"]).jsonPrimitive.content)
        val adjustmentId = job.payload["adjustmentId"]?.jsonPrimitive?.content?.let(UUID::fromString)
        val (userId, message) = db.transaction { tx -> render(tx, paymentId, notice, adjustmentId) } ?: return
        val recipient = contacts.find(userId) ?: return
        email.sendTransactional(recipient, message(recipient))
    }

    private fun render(tx: DSLContext, paymentId: UUID, notice: PaymentEmailJob.Notice, adjustmentId: UUID?): Pair<UUID, (Recipient) -> RenderedEmail>? {
        val p = tx.selectFrom(PAYMENT).where(PAYMENT.ID.eq(paymentId)).fetchOne() ?: return null
        val hoa = requireNotNull(Hoas.find(tx, p.hoaId))
        val unit = Properties.unitLabel(tx, p.hoaId, p.propertyId) ?: return null
        val adjustment = adjustmentId?.let { tx.selectFrom(PAYMENT_ADJUSTMENT).where(PAYMENT_ADJUSTMENT.ID.eq(it)).fetchOne() }
        val view = PaymentEmail.View(
            hoaName = hoa.name,
            unit = unit,
            reference = p.reference,
            method = requireNotNull(PaymentMethod.fromCode(p.method)),
            duesCents = p.duesCents,
            feeCents = p.feeCents,
            totalCents = p.totalCents,
            receivedOn = p.succeededAt?.toInstant()?.atZone(ZoneId.of(hoa.timeZone))?.toLocalDate(),
            failureReason = p.failureReason,
            adjustmentAmountCents = adjustment?.amountCents,
            adjustmentDuesCents = adjustment?.duesCents,
            adjustmentReference = adjustment?.reference,
            recurring = p.recurringPlanId != null,
        )
        return p.userId to { to: Recipient -> PaymentEmail.compose(to, notice, view) }
    }
}

/** Payment email templates (ED-36): plain text, kept here, never at the provider. Amounts only; no card or bank details. */
internal object PaymentEmail {
    data class View(
        val hoaName: String,
        val unit: String,
        val reference: String,
        val method: PaymentMethod,
        val duesCents: Long,
        val feeCents: Long,
        val totalCents: Long,
        val receivedOn: java.time.LocalDate?,
        val failureReason: String?,
        val adjustmentAmountCents: Long?,
        val adjustmentDuesCents: Long?,
        val adjustmentReference: String?,
        /** Made by a recurring plan (ED-59). */
        val recurring: Boolean = false,
    )

    private val date = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)

    fun compose(to: Recipient, notice: PaymentEmailJob.Notice, v: View): RenderedEmail {
        val hello = to.name?.let { "Hello $it," } ?: "Hello,"
        val method = if (v.method == PaymentMethod.CARD) "card" else "bank account"
        val amounts = buildString {
            append("  HOA dues:      ${Money.format(v.duesCents)}\n")
            if (v.feeCents > 0) append("  Card fee:      ${Money.format(v.feeCents)}\n")
            append("  Total charged: ${Money.format(v.totalCents)} ($method)")
        }
        val footer = "Reference: ${v.reference}. Questions? Contact your HOA board and quote this reference."
        return when (notice) {
            PaymentEmailJob.Notice.RECEIVED -> RenderedEmail(
                subject = "Payment received: ${Money.format(v.duesCents)} for ${v.unit}",
                text = """
                    |$hello
                    |
                    |${v.hoaName} received your payment for ${v.unit}${v.receivedOn?.let { " on ${date.format(it)}" }.orEmpty()}. This email is your receipt.
                    |
                    |$amounts
                    |
                    |Your account was credited ${Money.format(v.duesCents)}.
                    |
                    |$footer
                """.trimMargin(),
            )
            PaymentEmailJob.Notice.FAILED -> RenderedEmail(
                subject = "Your payment for ${v.unit} did not go through",
                text = """
                    |$hello
                    |
                    |Your ${if (v.recurring) "recurring " else ""}payment of ${Money.format(v.totalCents)} for ${v.unit} to ${v.hoaName} did not go through. ${PaymentFailures.describe(v.failureReason, v.method)}
                    |${
                    if (v.recurring) {
                        "Nothing was credited to your account. This charge will not be tried again; your recurring payment continues on its next date. You can pay this amount in the Seebrook app, and check or change your saved payment method there."
                    } else {
                        "Nothing was credited to your account. You can try again in the Seebrook app, with the same or another payment method."
                    }
                    }
                    |
                    |$footer
                """.trimMargin(),
            )
            PaymentEmailJob.Notice.REFUNDED -> RenderedEmail(
                subject = "Refund issued for your payment ${v.reference}",
                text = """
                    |$hello
                    |
                    |${v.hoaName} refunded ${Money.format(v.adjustmentAmountCents ?: 0)} of your payment ${v.reference} for ${v.unit}, to the same $method.
                    |Your account balance increased by ${Money.format(v.adjustmentDuesCents ?: 0)}. Refunds can take several business days to appear.
                    |
                    |Reference: ${v.adjustmentReference}. Questions? Contact your HOA board and quote this reference.
                """.trimMargin(),
            )
            PaymentEmailJob.Notice.REFUND_FAILED -> RenderedEmail(
                subject = "A refund for your payment ${v.reference} did not go through",
                text = """
                    |$hello
                    |
                    |A refund of ${Money.format(v.adjustmentAmountCents ?: 0)} for your payment ${v.reference} (${v.unit}) could not be completed, so the money stayed with ${v.hoaName}.
                    |Your account balance was restored by ${Money.format(-(v.adjustmentDuesCents ?: 0))}. Your HOA board can help with next steps.
                    |
                    |Reference: ${v.adjustmentReference}.
                """.trimMargin(),
            )
            PaymentEmailJob.Notice.RETURNED -> RenderedEmail(
                subject = "Your payment ${v.reference} was returned",
                text = """
                    |$hello
                    |
                    |Your payment ${v.reference} for ${v.unit} was returned or disputed through your ${if (v.method == PaymentMethod.CARD) "card issuer" else "bank"}, and ${Money.format(v.adjustmentAmountCents ?: 0)} went back to you.
                    |Your account balance increased by ${Money.format(v.adjustmentDuesCents ?: 0)}. If this was not intended, please contact your HOA board.
                    |
                    |Reference: ${v.adjustmentReference}.
                """.trimMargin(),
            )
            PaymentEmailJob.Notice.REINSTATED -> RenderedEmail(
                subject = "Your payment ${v.reference} was reinstated",
                text = """
                    |$hello
                    |
                    |The dispute on your payment ${v.reference} for ${v.unit} was resolved in favor of ${v.hoaName}. Your account was credited ${Money.format(-(v.adjustmentDuesCents ?: 0))} again.
                    |
                    |Reference: ${v.adjustmentReference}.
                """.trimMargin(),
            )
        }
    }
}
