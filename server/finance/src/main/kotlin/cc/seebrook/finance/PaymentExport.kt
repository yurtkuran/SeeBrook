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
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.tenant.Hoas
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.StringWriter
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Received online payments, and their refunds and returns (ED-45), as rows for the HOA's register workbook (ED-44), so the bookkeeper's
 * Excel and Seebrook stay in step. The columns are the register's, in its order, plus
 * `Seebrook Ref`. Only columns the bookkeeper types are filled; the register's formula columns
 * (balances, payee name, category, topic, year, month) are left blank, so pasting with "Skip
 * blanks" keeps the formulas. The import later recognizes each row by its `Seebrook Ref` and does
 * not count the payment twice.
 */
class ExportPayments(private val db: Database) {
    /**
     * One register row. [amountCents] uses the register's sign: positive is money received from
     * the owner, negative is money paid back (a refund or a returned payment).
     */
    data class Row(
        val reference: String,
        /** The unit's identifier in the HOA's register (its account). */
        val account: String,
        /** The day the money moved, in the HOA's time zone. */
        val date: LocalDate,
        val amountCents: Long,
        /** The fee the owner paid on top, for a payment; 0 otherwise. */
        val feeCents: Long,
        val comment: String,
    )

    sealed interface Result {
        data class Exported(val rows: List<Row>) : Result
        data object InvalidRange : Result
        data object Forbidden : Result
    }

    /** Payments received from [from] to [to], both inclusive, in the HOA's time zone; oldest first. */
    operator fun invoke(actor: UserActor, hoaId: UUID, from: LocalDate, to: LocalDate): Result {
        if (!actor.can(Permission.MANAGE_FINANCES, hoaId)) return Result.Forbidden
        if (to.isBefore(from) || from.plusYears(1).isBefore(to)) return Result.InvalidRange
        return db.transaction { tx ->
            val zone = ZoneId.of(requireNotNull(Hoas.find(tx, hoaId)).timeZone)
            val start = from.atStartOfDay(zone).toOffsetDateTime()
            val end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
            val payments = tx.select(PAYMENT.asterisk(), ACCOUNT.EXTERNAL_ID).from(PAYMENT)
                .join(ACCOUNT).on(ACCOUNT.ID.eq(PAYMENT.ACCOUNT_ID))
                .where(PAYMENT.HOA_ID.eq(hoaId)).and(PAYMENT.STATUS.eq(PaymentStatus.SUCCEEDED.code))
                .and(PAYMENT.SUCCEEDED_AT.ge(start)).and(PAYMENT.SUCCEEDED_AT.lt(end))
                .fetch { r ->
                    val method = requireNotNull(PaymentMethod.fromCode(r.get(PAYMENT.METHOD)))
                    val fee = r.get(PAYMENT.FEE_CENTS)
                    r.get(PAYMENT.SUCCEEDED_AT).toInstant() to Row(
                        reference = r.get(PAYMENT.REFERENCE),
                        account = r.get(ACCOUNT.EXTERNAL_ID),
                        date = r.get(PAYMENT.SUCCEEDED_AT).atZoneSameInstant(zone).toLocalDate(),
                        amountCents = r.get(PAYMENT.DUES_CENTS),
                        feeCents = fee,
                        comment = buildString {
                            append("Online payment by ${if (method == PaymentMethod.CARD) "card" else "bank account"}")
                            if (fee > 0) append("; fee ${Money.format(fee)}; total ${Money.format(r.get(PAYMENT.TOTAL_CENTS))}")
                        },
                    )
                }
            // Refunds and returns that changed a unit's balance (ED-45); fee-only ones do not belong on a unit's row.
            val adjustments = tx.select(PAYMENT_ADJUSTMENT.asterisk(), PAYMENT.REFERENCE, ACCOUNT.EXTERNAL_ID).from(PAYMENT_ADJUSTMENT)
                .join(PAYMENT).on(PAYMENT.ID.eq(PAYMENT_ADJUSTMENT.PAYMENT_ID))
                .join(ACCOUNT).on(ACCOUNT.ID.eq(PAYMENT.ACCOUNT_ID))
                .where(PAYMENT_ADJUSTMENT.HOA_ID.eq(hoaId)).and(PAYMENT_ADJUSTMENT.DUES_CENTS.ne(0L))
                .and(PAYMENT_ADJUSTMENT.CREATED_AT.ge(start)).and(PAYMENT_ADJUSTMENT.CREATED_AT.lt(end))
                .fetch { r ->
                    val kind = requireNotNull(AdjustmentKind.fromCode(r.get(PAYMENT_ADJUSTMENT.KIND)))
                    r.get(PAYMENT_ADJUSTMENT.CREATED_AT).toInstant() to Row(
                        reference = r.get(PAYMENT_ADJUSTMENT.REFERENCE),
                        account = r.get(ACCOUNT.EXTERNAL_ID),
                        date = r.get(PAYMENT_ADJUSTMENT.CREATED_AT).atZoneSameInstant(zone).toLocalDate(),
                        amountCents = -r.get(PAYMENT_ADJUSTMENT.DUES_CENTS),
                        feeCents = 0,
                        comment = PaymentStore.adjustmentDescription(kind, r.get(PAYMENT.REFERENCE)) +
                            "; ${Money.format(r.get(PAYMENT_ADJUSTMENT.AMOUNT_CENTS))} moved",
                    )
                }
            val rows = (payments + adjustments).sortedWith(compareBy({ it.first }, { it.second.reference })).map { it.second }
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "finance.payments_exported",
                    hoaId = hoaId,
                    targetType = "hoa",
                    targetId = hoaId,
                    details = buildJsonObject {
                        put("from", from.toString())
                        put("to", to.toString())
                        put("payments", rows.size)
                    },
                ),
            )
            Result.Exported(rows)
        }
    }
}

/** Writes [ExportPayments] rows in the register's layout (ED-44). */
object RegisterPaymentRows {
    /** The register's header, in order, plus `Seebrook Ref`. */
    val HEADER = listOf(
        "transactionId", "ID", "Posted Date", "Transaction Date", "Transaction Type", "Account", "Check/Serial #", "Amount",
        "Posted Balance", "Posted Balance", "Balance", "Payee ID", "Payee", "Invoice Number", "Category", "Topic", "Year", "Month",
        "Comment", RegisterWorkbook.SEEBROOK_REF,
    )
    const val TRANSACTION_TYPE = "Online Payment"
    private val DATE = DateTimeFormatter.ofPattern("M/d/yyyy")
    private val NUMBERED = Regex("^(.*?)(\\d+)$")

    /**
     * [firstId], e.g. `2026-00391`, numbers the rows in order (`2026-00392`, …); without it
     * `transactionId` is left for the bookkeeper. [categoryId] is the register's `ID` code for
     * HOA dues.
     */
    fun csv(rows: List<ExportPayments.Row>, categoryId: String, firstId: String?): ByteArray {
        val ids = firstId?.let { first ->
            val (prefix, digits) = requireNotNull(NUMBERED.matchEntire(first)) { "first ID must end in a number" }.destructured
            val start = digits.toLong()
            rows.indices.map { i -> prefix + (start + i).toString().padStart(digits.length, '0') }
        }
        val out = StringWriter()
        CSVPrinter(out, CSVFormat.DEFAULT.builder().setRecordSeparator("\r\n").get()).use { printer ->
            printer.printRecord(HEADER)
            rows.forEachIndexed { i, r ->
                printer.printRecord(
                    ids?.get(i).orEmpty(), categoryId, "", r.date.format(DATE), TRANSACTION_TYPE, "", "",
                    plain(r.amountCents), "", "", "", r.account, "", "", "", "", "", "", r.comment, r.reference,
                )
            }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun plain(cents: Long): String {
        val a = kotlin.math.abs(cents)
        return (if (cents < 0) "-" else "") + "${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }
}
