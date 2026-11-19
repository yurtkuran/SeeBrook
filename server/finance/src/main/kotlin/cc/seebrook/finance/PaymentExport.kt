package cc.seebrook.finance

import cc.seebrook.db.generated.finance.Tables.ACCOUNT
import cc.seebrook.db.generated.finance.Tables.PAYMENT
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
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
 * Received online payments, as rows for the HOA's register workbook (ED-44), so the bookkeeper's
 * Excel and Seebrook stay in step. The columns are the register's, in its order, plus
 * `Seebrook Ref`. Only columns the bookkeeper types are filled; the register's formula columns
 * (balances, payee name, category, topic, year, month) are left blank, so pasting with "Skip
 * blanks" keeps the formulas. The import later recognizes each row by its `Seebrook Ref` and does
 * not count the payment twice.
 */
class ExportPayments(private val db: Database) {
    data class Row(
        val reference: String,
        /** The unit's identifier in the HOA's register (its account). */
        val account: String,
        /** The day the payment was received, in the HOA's time zone. */
        val receivedOn: LocalDate,
        val method: PaymentMethod,
        val duesCents: Long,
        val feeCents: Long,
        val totalCents: Long,
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
            val rows = tx.select(PAYMENT.asterisk(), ACCOUNT.EXTERNAL_ID).from(PAYMENT)
                .join(ACCOUNT).on(ACCOUNT.ID.eq(PAYMENT.ACCOUNT_ID))
                .where(PAYMENT.HOA_ID.eq(hoaId)).and(PAYMENT.STATUS.eq(PaymentStatus.SUCCEEDED.code))
                .and(PAYMENT.SUCCEEDED_AT.ge(start)).and(PAYMENT.SUCCEEDED_AT.lt(end))
                .orderBy(PAYMENT.SUCCEEDED_AT, PAYMENT.REFERENCE)
                .fetch { r ->
                    Row(
                        reference = r.get(PAYMENT.REFERENCE),
                        account = r.get(ACCOUNT.EXTERNAL_ID),
                        receivedOn = r.get(PAYMENT.SUCCEEDED_AT).atZoneSameInstant(zone).toLocalDate(),
                        method = requireNotNull(PaymentMethod.fromCode(r.get(PAYMENT.METHOD))),
                        duesCents = r.get(PAYMENT.DUES_CENTS),
                        feeCents = r.get(PAYMENT.FEE_CENTS),
                        totalCents = r.get(PAYMENT.TOTAL_CENTS),
                    )
                }
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
                val method = if (r.method == PaymentMethod.CARD) "card" else "bank account"
                val comment = buildString {
                    append("Online payment by $method")
                    if (r.feeCents > 0) append("; fee ${Money.format(r.feeCents)}; total ${Money.format(r.totalCents)}")
                }
                printer.printRecord(
                    ids?.get(i).orEmpty(), categoryId, "", r.receivedOn.format(DATE), TRANSACTION_TYPE, "", "",
                    plain(r.duesCents), "", "", "", r.account, "", "", "", "", "", "", comment, r.reference,
                )
            }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun plain(cents: Long) = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}
