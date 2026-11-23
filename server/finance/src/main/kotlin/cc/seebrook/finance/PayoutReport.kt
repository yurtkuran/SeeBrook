package cc.seebrook.finance

import cc.seebrook.db.generated.finance.Tables.ACCOUNT
import cc.seebrook.db.generated.finance.Tables.PAYMENT
import cc.seebrook.db.generated.finance.Tables.PAYMENT_ADJUSTMENT
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.payments.AdjustmentKind
import cc.seebrook.platform.payments.PaymentProviderException
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.PaymentRequestRejectedException
import cc.seebrook.platform.payments.PayoutStatus
import cc.seebrook.platform.payments.ProviderPayout
import cc.seebrook.platform.payments.SettlementKind
import cc.seebrook.platform.payments.SettlementLine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.jooq.DSLContext
import java.io.StringWriter
import java.time.LocalDate
import java.util.UUID

/**
 * What each bank deposit from the payment provider is made of (ED-47, GA-22): payments received
 * (gross), refunds and returns, the provider's actual fees and the net that reached the bank, each
 * line matched to Seebrook's payment or adjustment by the provider's own IDs. Read-only: it
 * changes nothing and reports anything that does not match for investigation (GA-18).
 */
class PayoutReport(private val db: Database, private val providers: PaymentProviders) {
    data class Line(
        val kind: SettlementKind,
        /** Seebrook's reference (`PAY-…`, `PAY-…-R1`), when matched. */
        val reference: String?,
        /** The unit's account in the HOA's register, when matched. */
        val account: String?,
        val grossCents: Long,
        /** The provider's fee; positive is a cost to the HOA. */
        val providerFeeCents: Long,
        val netCents: Long,
        /** For a matched payment: its dues and the fee the owner paid on top. */
        val duesCents: Long?,
        val ownerFeeCents: Long?,
        /** Why this line needs a look; null when it matches. */
        val problem: String?,
    )

    data class Payout(
        val payoutId: String,
        val arrivalDate: LocalDate,
        val status: PayoutStatus,
        val currency: String,
        val depositCents: Long,
        val lines: List<Line>,
    ) {
        val paymentsCents: Long get() = lines.filter { it.kind == SettlementKind.PAYMENT }.sumOf { it.grossCents }
        val refundsCents: Long get() = lines.filter { it.kind in REVERSALS }.sumOf { it.grossCents }

        /** Everything the provider charged: fees on lines plus separately billed fees (negative). */
        val providerFeesCents: Long get() = -lines.sumOf { it.providerFeeCents } + lines.filter { it.kind == SettlementKind.FEE }.sumOf { it.grossCents }
        val otherCents: Long get() = lines.filter { it.kind == SettlementKind.OTHER }.sumOf { it.grossCents }
        val netCents: Long get() = lines.sumOf { it.netCents }
        val duesCents: Long get() = lines.sumOf { it.duesCents ?: 0 }
        val ownerFeesCents: Long get() = lines.sumOf { it.ownerFeeCents ?: 0 }

        /** What processing cost the HOA: the provider's fees less what owners paid toward them. */
        val processingCostCents: Long get() = -providerFeesCents - ownerFeesCents

        val problems: List<String> get() = buildList {
            if (netCents != depositCents) add("lines add up to ${Money.format(netCents)}, not the deposit of ${Money.format(depositCents)}")
            lines.mapNotNull { l -> l.problem?.let { "${l.reference ?: l.kind.name.lowercase()} ${Money.format(l.grossCents)}: $it" } }.forEach(::add)
        }
    }

    sealed interface Result {
        data class Report(val provider: String, val payouts: List<Payout>) : Result
        data object Forbidden : Result
        data object InvalidRange : Result
        data object NotConfigured : Result
        data object ProviderUnavailable : Result
        data class ProviderRefused(val message: String) : Result
    }

    /** Payouts arriving at the bank on [from] through [to], inclusive, oldest first. */
    operator fun invoke(actor: UserActor, hoaId: UUID, from: LocalDate, to: LocalDate): Result {
        if (!actor.can(Permission.MANAGE_FINANCES, hoaId)) return Result.Forbidden
        if (to.isBefore(from) || from.plusYears(1).isBefore(to)) return Result.InvalidRange
        val settings = db.transaction { tx -> PaymentStore.settings(tx, hoaId) } ?: return Result.NotConfigured
        val provider = providers[settings.provider] ?: return Result.NotConfigured

        // The provider is asked outside any database transaction.
        val fetched = try {
            provider.payouts(settings.merchantRef, from, to).map { it to provider.payoutLines(settings.merchantRef, it.payoutId) }
        } catch (e: PaymentRequestRejectedException) {
            return Result.ProviderRefused(e.message ?: "refused")
        } catch (e: PaymentProviderException) {
            return Result.ProviderUnavailable
        }
        return db.transaction { tx ->
            val payouts = fetched.map { (payout, lines) -> match(tx, hoaId, settings, payout, lines) }
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "finance.payout_report",
                    hoaId = hoaId,
                    targetType = "hoa",
                    targetId = hoaId,
                    details = buildJsonObject {
                        put("from", from.toString())
                        put("to", to.toString())
                        put("payouts", payouts.size)
                        put("problems", payouts.sumOf { it.problems.size })
                    },
                ),
            )
            Result.Report(provider.name, payouts)
        }
    }

    private fun match(tx: DSLContext, hoaId: UUID, settings: PaymentSettings, payout: ProviderPayout, lines: List<SettlementLine>): Payout {
        val paymentIds = lines.filter { it.kind == SettlementKind.PAYMENT }.mapNotNull { it.providerPaymentId }
        val payments = if (paymentIds.isEmpty()) {
            emptyMap()
        } else {
            tx.select(PAYMENT.asterisk(), ACCOUNT.EXTERNAL_ID).from(PAYMENT).join(ACCOUNT).on(ACCOUNT.ID.eq(PAYMENT.ACCOUNT_ID))
                .where(PAYMENT.HOA_ID.eq(hoaId)).and(PAYMENT.PROVIDER.eq(settings.provider)).and(PAYMENT.MERCHANT_REF.eq(settings.merchantRef))
                .and(PAYMENT.PROVIDER_PAYMENT_ID.`in`(paymentIds))
                .fetch().associateBy { it.get(PAYMENT.PROVIDER_PAYMENT_ID) }
        }
        val sourceRefs = lines.filter { it.kind in REVERSALS }.mapNotNull { it.sourceRef }
        val adjustments = if (sourceRefs.isEmpty()) {
            emptyMap()
        } else {
            tx.select(PAYMENT_ADJUSTMENT.KIND, PAYMENT_ADJUSTMENT.PROVIDER_REF, PAYMENT_ADJUSTMENT.REFERENCE, ACCOUNT.EXTERNAL_ID)
                .from(PAYMENT_ADJUSTMENT).join(PAYMENT).on(PAYMENT.ID.eq(PAYMENT_ADJUSTMENT.PAYMENT_ID))
                .join(ACCOUNT).on(ACCOUNT.ID.eq(PAYMENT.ACCOUNT_ID))
                .where(PAYMENT_ADJUSTMENT.HOA_ID.eq(hoaId)).and(PAYMENT.MERCHANT_REF.eq(settings.merchantRef))
                .and(PAYMENT_ADJUSTMENT.PROVIDER_REF.`in`(sourceRefs))
                .fetch().associateBy { it.value1() to it.value2() }
        }
        val matched = lines.map { line ->
            fun unmatched(problem: String?) = Line(line.kind, null, null, line.grossCents, line.feeCents, line.netCents, null, null, problem)
            if (!line.currency.equals(payout.currency, ignoreCase = true)) return@map unmatched("in ${line.currency}, not ${payout.currency}")
            when (line.kind) {
                SettlementKind.PAYMENT -> {
                    val p = line.providerPaymentId?.let { payments[it] } ?: return@map unmatched("not a payment Seebrook started")
                    val total = p.get(PAYMENT.TOTAL_CENTS)
                    val status = p.get(PAYMENT.STATUS)
                    Line(
                        line.kind, p.get(PAYMENT.REFERENCE), p.get(ACCOUNT.EXTERNAL_ID), line.grossCents, line.feeCents, line.netCents,
                        p.get(PAYMENT.DUES_CENTS), p.get(PAYMENT.FEE_CENTS),
                        when {
                            status != PaymentStatus.SUCCEEDED.code -> "paid out, but $status in Seebrook (run reconcile-payments)"
                            total != line.grossCents -> "charged ${Money.format(line.grossCents)}, Seebrook expected ${Money.format(total)}"
                            else -> null
                        },
                    )
                }
                SettlementKind.REFUND, SettlementKind.REFUND_FAILED, SettlementKind.DISPUTE, SettlementKind.DISPUTE_REVERSAL -> {
                    val kind = ADJUSTMENT_KIND.getValue(line.kind)
                    val a = line.sourceRef?.let { adjustments[kind.code to it] }
                        ?: return@map unmatched("${kind.code.replace('_', ' ')} not recorded in Seebrook (check the webhook)")
                    Line(line.kind, a.value3(), a.value4(), line.grossCents, line.feeCents, line.netCents, null, null, null)
                }
                SettlementKind.FEE -> unmatched(null)
                SettlementKind.OTHER -> unmatched("not a payment, refund, dispute or fee: check it in the provider's dashboard")
            }
        }
        return Payout(payout.payoutId, payout.arrivalDate, payout.status, payout.currency, payout.amountCents, matched)
    }

    companion object {
        val REVERSALS = setOf(SettlementKind.REFUND, SettlementKind.REFUND_FAILED, SettlementKind.DISPUTE, SettlementKind.DISPUTE_REVERSAL)
        private val ADJUSTMENT_KIND = mapOf(
            SettlementKind.REFUND to AdjustmentKind.REFUND,
            SettlementKind.REFUND_FAILED to AdjustmentKind.REFUND_FAILED,
            SettlementKind.DISPUTE to AdjustmentKind.DISPUTE_WITHDRAWN,
            SettlementKind.DISPUTE_REVERSAL to AdjustmentKind.DISPUTE_REINSTATED,
        )

        /** One row per payout line, for a spreadsheet. Amounts are plain decimals; positive is money to the HOA. */
        fun csv(payouts: List<Payout>): ByteArray {
            val out = StringWriter()
            CSVPrinter(out, CSVFormat.DEFAULT.builder().setRecordSeparator("\r\n").get()).use { p ->
                p.printRecord("payout", "arrival_date", "payout_status", "type", "reference", "account", "gross", "provider_fee", "net", "dues", "owner_fee", "problem")
                payouts.forEach { po ->
                    po.lines.forEach { l ->
                        p.printRecord(
                            po.payoutId, po.arrivalDate, po.status.name.lowercase(), l.kind.name.lowercase(), l.reference.orEmpty(), l.account.orEmpty(),
                            plain(l.grossCents), plain(-l.providerFeeCents), plain(l.netCents),
                            l.duesCents?.let(::plain).orEmpty(), l.ownerFeeCents?.let(::plain).orEmpty(), l.problem.orEmpty(),
                        )
                    }
                }
            }
            return out.toString().toByteArray(Charsets.UTF_8)
        }

        private fun plain(cents: Long): String {
            val a = kotlin.math.abs(cents)
            return (if (cents < 0) "-" else "") + "${a / 100}.${(a % 100).toString().padStart(2, '0')}"
        }
    }
}
