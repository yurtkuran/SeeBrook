package cc.seebrook.finance

import cc.seebrook.db.generated.finance.Tables.IMPORT_BATCH
import cc.seebrook.db.generated.finance.Tables.LEDGER_ENTRY
import cc.seebrook.db.generated.finance.Tables.TRANSACTION
import cc.seebrook.db.generated.finance.Tables.TRANSACTION_CORRECTION
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.JSON
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

/** Transaction types (ED-41). Charges are positive, payments and waivers negative, adjustments either. */
enum class TransactionType(val code: String) {
    ASSESSMENT("assessment"),
    LATE_FEE("late_fee"),
    ADJUSTMENT("adjustment"),
    WAIVER("waiver"),
    PAYMENT("payment"),
    ;

    fun allows(amountCents: Long): Boolean = when (this) {
        ASSESSMENT, LATE_FEE -> amountCents > 0
        WAIVER, PAYMENT -> amountCents < 0
        ADJUSTMENT -> amountCents != 0L
    }

    companion object {
        fun fromCode(code: String): TransactionType? = entries.firstOrNull { it.code == code.trim().lowercase() }
    }
}

/** The values of a transaction a later import may correct. The account is not one of them. */
data class TransactionValues(
    val type: TransactionType,
    val date: LocalDate,
    val dueDate: LocalDate?,
    val description: String,
    val amountCents: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", type.code)
        put("date", date.toString())
        put("dueDate", dueDate?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("description", description)
        put("amountCents", amountCents)
    }

    /** Names of the fields that differ, for the dry-run report. */
    fun changedFields(other: TransactionValues): List<String> = buildList {
        if (type != other.type) add("type")
        if (date != other.date) add("date")
        if (dueDate != other.dueDate) add("due_date")
        if (description != other.description) add("description")
        if (amountCents != other.amountCents) add("amount")
    }
}

/**
 * One file row. [paymentRef] marks the HOA's record of an online payment Seebrook already holds
 * (`PAY-…`, ED-44): it is checked against that payment and never posted again.
 */
data class TransactionRow(
    val line: Long,
    val externalId: String,
    val account: String,
    val values: TransactionValues,
    val paymentRef: String? = null,
)

/**
 * Parses and validates a transaction file (ED-41). Columns: `transaction_id, account, date, type,
 * description, amount` and optional `due_date`. Every problem is reported with its line; any
 * problem rejects the whole file. An amount of 0 is allowed: it voids a known transaction (ED-42).
 */
object TransactionCsv {
    val COLUMNS = listOf("transaction_id", "account", "date", "type", "description", "amount", "due_date", "payment_ref")
    private val PAYMENT_REF = Regex("^PAY-[0-9A-Z]{8}$")

    sealed interface Parsed {
        data class Valid(val rows: List<TransactionRow>) : Parsed
        data class Invalid(val errors: List<RowError>) : Parsed
    }

    fun parse(content: ByteArray): Parsed {
        val rows = when (val read = FinanceCsv.read(content, COLUMNS, optional = setOf("due_date", "payment_ref"))) {
            is FinanceCsv.Read.Invalid -> return Parsed.Invalid(read.errors)
            is FinanceCsv.Read.Rows -> read.rows
        }
        val errors = mutableListOf<RowError>()
        val parsed = rows.mapNotNull { row -> parseRow(row, errors) }
        // Over every row, valid or not, so a repeat of a broken row is reported too.
        rows.filter { it["transaction_id"] != null }.groupBy { it["transaction_id"] }.values.filter { it.size > 1 }.forEach { dupes ->
            dupes.drop(1).forEach { errors += RowError(it.line, "transaction_id", "repeats line ${dupes.first().line}") }
        }
        rows.filter { it["payment_ref"] != null }.groupBy { it["payment_ref"] }.values.filter { it.size > 1 }.forEach { dupes ->
            dupes.drop(1).forEach { errors += RowError(it.line, "payment_ref", "repeats line ${dupes.first().line}") }
        }
        return if (errors.isEmpty()) Parsed.Valid(parsed) else Parsed.Invalid(errors.sortedBy { it.line })
    }

    private fun parseRow(row: CsvRow, errors: MutableList<RowError>): TransactionRow? {
        val before = errors.size
        fun required(column: String): String? = row[column].also { if (it == null) errors += RowError(row.line, column, "is required") }

        val id = required("transaction_id")
        if (id != null && !validIdentifier(id)) errors += RowError(row.line, "transaction_id", "is longer than 64 characters or has control characters")
        val account = required("account")
        if (account != null && !validIdentifier(account)) errors += RowError(row.line, "account", "is longer than 64 characters or has control characters")
        val date = required("date")?.let { text ->
            CsvDates.parse(text).also { if (it == null) errors += RowError(row.line, "date", "'$text' is not a date (YYYY-MM-DD or M/D/YYYY)") }
        }
        val dueDate = row["due_date"]?.let { text ->
            CsvDates.parse(text).also { if (it == null) errors += RowError(row.line, "due_date", "'$text' is not a date (YYYY-MM-DD or M/D/YYYY)") }
        }
        val type = required("type")?.let { text ->
            TransactionType.fromCode(text).also {
                if (it == null) errors += RowError(row.line, "type", "'$text' is not one of ${TransactionType.entries.joinToString { t -> t.code }}")
            }
        }
        val description = required("description")
        if (description != null && description.length > 200) errors += RowError(row.line, "description", "is longer than 200 characters")
        val amount = required("amount")?.let { text ->
            Money.parseCents(text).also {
                if (it == null) errors += RowError(row.line, "amount", "'$text' is not an amount in dollars and cents")
            }
        }
        if (type != null && amount != null && amount != 0L && !type.allows(amount)) {
            val expected = if (amount > 0) "a negative" else "a positive"
            errors += RowError(row.line, "amount", "${type.code} needs $expected amount (charges are positive, payments and waivers negative)")
        }
        if (dueDate != null && amount != null && amount < 0) errors += RowError(row.line, "due_date", "is only for charges")
        val paymentRef = row["payment_ref"]?.uppercase()
        if (paymentRef != null) {
            if (!PAYMENT_REF.matches(paymentRef)) errors += RowError(row.line, "payment_ref", "'${paymentRef.take(20)}' is not a Seebrook payment reference (PAY-XXXXXXXX)")
            if (type != null && type != TransactionType.PAYMENT) errors += RowError(row.line, "type", "an online payment (payment_ref) must be a payment")
            if (amount != null && amount >= 0) errors += RowError(row.line, "amount", "an online payment (payment_ref) must be negative")
        }

        if (errors.size != before) return null
        return TransactionRow(
            row.line, requireNotNull(id), requireNotNull(account),
            TransactionValues(requireNotNull(type), requireNotNull(date), dueDate, requireNotNull(description), requireNotNull(amount)),
            paymentRef,
        )
    }
}

/**
 * Imports assessments, fees, adjustments, waivers and offline payments from the HOA's spreadsheet
 * (GA-14, GA-15, GA-21, ED-41):
 *  - the whole file is validated and planned first; any error or conflict rejects all of it;
 *  - a new transaction ID creates a transaction and its ledger entry;
 *  - a known ID with the same values is unchanged; with different values it is a correction,
 *    kept as immutable before/after history, and an amount change posts a reversal and a new
 *    entry rather than rewriting the ledger;
 *  - a known ID on a different account is a conflict: moving money between properties is never
 *    inferred from a file;
 *  - transactions missing from the file are left alone (files may be partial);
 *  - an amount of 0 voids a known transaction: only its amount changes, through a correction with
 *    a reversal; a new ID with 0 has nothing to post and is skipped (ED-42);
 *  - a row with a `payment_ref` is the HOA's record of an online payment: it must match a
 *    received payment of the same unit and amount, and is never posted, since the payment already
 *    credited the ledger (ED-44);
 *  - the same file twice, or any file already applied, changes nothing.
 * Dry run by default; a commit is applied in one transaction under a per-HOA lock.
 */
class ImportTransactions(private val db: Database) {
    data class Correction(val row: TransactionRow, val transactionId: UUID, val accountId: UUID, val before: TransactionValues) {
        val changed get() = before.changedFields(row.values)
    }

    data class Plan(
        val rowCount: Int,
        val toCreate: List<TransactionRow>,
        val toCorrect: List<Correction>,
        val unchanged: Int,
        /** New transaction IDs with amount 0: nothing to post (ED-42). */
        val zeroSkipped: Int,
        /** Rows recording online payments Seebrook already credited: checked, never posted (ED-44). */
        val onlinePaymentsMatched: Int,
        /** The total change to balances across all accounts, in cents. */
        val netChangeCents: Long,
        val accountsAffected: Int,
    ) {
        val isEmpty get() = toCreate.isEmpty() && toCorrect.isEmpty()

        /** Corrections that void a transaction (new amount 0). */
        val voids get() = toCorrect.count { it.row.values.amountCents == 0L && it.before.amountCents != 0L }
    }

    sealed interface Result {
        data class Invalid(val errors: List<RowError>) : Result
        data class Conflicts(val conflicts: List<String>) : Result
        data class Planned(val plan: Plan) : Result
        data class Imported(val batchId: UUID, val plan: Plan) : Result
        data class AlreadyImported(val batchId: UUID, val importedAt: Instant) : Result
        data object NothingToImport : Result
        data object Forbidden : Result
        data object ReasonRequired : Result
    }

    operator fun invoke(
        actor: UserActor,
        hoaId: UUID,
        fileName: String,
        content: ByteArray,
        commit: Boolean,
        reason: String? = null,
    ): Result {
        if (!actor.can(Permission.MANAGE_FINANCES, hoaId)) return Result.Forbidden
        val why = reasonOf(reason)
        if (commit && why == null) return Result.ReasonRequired
        val rows = when (val parsed = TransactionCsv.parse(content)) {
            is TransactionCsv.Parsed.Invalid -> return Result.Invalid(parsed.errors)
            is TransactionCsv.Parsed.Valid -> parsed.rows
        }
        val checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))

        return db.transaction { tx ->
            FinanceLock.acquire(tx, hoaId)
            previousBatch(tx, hoaId, checksum)?.let { return@transaction it }
            val outcome = plan(tx, hoaId, rows)
            when {
                outcome !is Result.Planned || !commit -> outcome
                outcome.plan.isEmpty -> Result.NothingToImport
                else -> {
                    val batchId = apply(tx, actor, hoaId, fileName, checksum, outcome.plan, requireNotNull(why))
                    Result.Imported(batchId, outcome.plan)
                }
            }
        }
    }

    private fun previousBatch(tx: DSLContext, hoaId: UUID, checksum: String): Result.AlreadyImported? =
        tx.select(IMPORT_BATCH.ID, IMPORT_BATCH.IMPORTED_AT).from(IMPORT_BATCH)
            .where(IMPORT_BATCH.HOA_ID.eq(hoaId)).and(IMPORT_BATCH.FILE_SHA256.eq(checksum))
            .fetchOne { Result.AlreadyImported(requireNotNull(it.value1()), it.value2().toInstant()) }

    private data class Existing(val id: UUID, val accountId: UUID, val values: TransactionValues)

    private fun existing(tx: DSLContext, hoaId: UUID, externalIds: Collection<String>): Map<String, Existing> =
        externalIds.chunked(1000).flatMap { chunk ->
            tx.selectFrom(TRANSACTION).where(TRANSACTION.HOA_ID.eq(hoaId)).and(TRANSACTION.EXTERNAL_ID.`in`(chunk)).fetch()
        }.associate { t ->
            t.externalId to Existing(
                requireNotNull(t.id),
                requireNotNull(t.accountId),
                TransactionValues(requireNotNull(TransactionType.fromCode(t.type)), t.transactionDate, t.dueDate, t.description, t.amountCents),
            )
        }

    private fun plan(tx: DSLContext, hoaId: UUID, rows: List<TransactionRow>): Result {
        val accounts = AccountDirectory.byExternalId(tx, hoaId)
        val known = existing(tx, hoaId, rows.map { it.externalId })
        val conflicts = mutableListOf<String>()
        val toCreate = mutableListOf<TransactionRow>()
        val toCorrect = mutableListOf<Correction>()
        var unchanged = 0
        var zeroSkipped = 0
        var net = 0L
        val affected = mutableSetOf<UUID>()

        rows.filter { it.account !in accounts }.groupBy { it.account }.forEach { (account, unknown) ->
            conflicts += "Account '$account' is not mapped to a unit (lines ${unknown.joinToString(",") { it.line.toString() }}); " +
                "map it with import-accounts first"
        }
        val payments = PaymentStore.byReferences(tx, hoaId, rows.mapNotNull { it.paymentRef })
        var matched = 0
        for (fileRow in rows) {
            val account = accounts[fileRow.account] ?: continue
            val current = known[fileRow.externalId]
            fileRow.paymentRef?.let { ref ->
                // The HOA's record of an online payment: Seebrook's ledger already holds it (ED-44).
                val payment = payments[ref]
                when {
                    payment == null -> conflicts += "Line ${fileRow.line}: no online payment $ref in this HOA"
                    payment.status != PaymentStatus.SUCCEEDED ->
                        conflicts += "Line ${fileRow.line}: online payment $ref has not been received (${payment.status.code})"
                    payment.accountId != account.id ->
                        conflicts += "Line ${fileRow.line}: online payment $ref was made for another unit"
                    payment.duesCents != -fileRow.values.amountCents ->
                        conflicts += "Line ${fileRow.line}: online payment $ref was ${Money.format(payment.duesCents)}, not ${Money.format(-fileRow.values.amountCents)}"
                    current != null ->
                        conflicts += "Line ${fileRow.line}: transaction '${fileRow.externalId}' was already imported as a separate transaction; " +
                            "the online payment would count twice"
                    else -> matched++
                }
                continue
            }
            // A void changes only the amount; the transaction keeps its other values (ED-42).
            val row = if (fileRow.values.amountCents == 0L && current != null) {
                fileRow.copy(values = current.values.copy(amountCents = 0))
            } else {
                fileRow
            }
            when {
                current == null && row.values.amountCents == 0L -> zeroSkipped++
                current == null -> {
                    toCreate += row
                    net += row.values.amountCents
                    affected += account.id
                }
                current.accountId != account.id ->
                    conflicts += "Line ${row.line}: transaction '${row.externalId}' belongs to another account; " +
                        "moving a transaction between accounts is not allowed by import"
                current.values == row.values -> unchanged++
                else -> {
                    toCorrect += Correction(row, current.id, account.id, current.values)
                    net += row.values.amountCents - current.values.amountCents
                    if (row.values.amountCents != current.values.amountCents) affected += account.id
                }
            }
        }
        if (conflicts.isNotEmpty()) return Result.Conflicts(conflicts)
        return Result.Planned(Plan(rows.size, toCreate, toCorrect, unchanged, zeroSkipped, matched, net, affected.size))
    }

    private fun apply(
        tx: DSLContext,
        actor: UserActor,
        hoaId: UUID,
        fileName: String,
        checksum: String,
        plan: Plan,
        reason: String,
    ): UUID {
        val batchId = requireNotNull(
            tx.insertInto(IMPORT_BATCH)
                .set(IMPORT_BATCH.HOA_ID, hoaId)
                .set(IMPORT_BATCH.FILE_NAME, fileName.take(255))
                .set(IMPORT_BATCH.FILE_SHA256, checksum)
                .set(IMPORT_BATCH.ROW_COUNT, plan.rowCount)
                .set(IMPORT_BATCH.CREATED_COUNT, plan.toCreate.size)
                .set(IMPORT_BATCH.CORRECTED_COUNT, plan.toCorrect.size)
                .set(IMPORT_BATCH.UNCHANGED_COUNT, plan.unchanged + plan.zeroSkipped + plan.onlinePaymentsMatched)
                .set(IMPORT_BATCH.IMPORTED_BY, actor.userId)
                .set(IMPORT_BATCH.REASON, reason)
                .returning(IMPORT_BATCH.ID).fetchSingle().id,
        )
        val accounts = AccountDirectory.byExternalId(tx, hoaId)

        fun post(accountId: UUID, transactionId: UUID, amount: Long, kind: String) {
            tx.insertInto(LEDGER_ENTRY)
                .set(LEDGER_ENTRY.HOA_ID, hoaId)
                .set(LEDGER_ENTRY.ACCOUNT_ID, accountId)
                .set(LEDGER_ENTRY.AMOUNT_CENTS, amount)
                .set(LEDGER_ENTRY.KIND, kind)
                .set(LEDGER_ENTRY.TRANSACTION_ID, transactionId)
                .set(LEDGER_ENTRY.BATCH_ID, batchId)
                .execute()
        }

        for (row in plan.toCreate) {
            val accountId = accounts.getValue(row.account).id
            val v = row.values
            val id = requireNotNull(
                tx.insertInto(TRANSACTION)
                    .set(TRANSACTION.HOA_ID, hoaId)
                    .set(TRANSACTION.ACCOUNT_ID, accountId)
                    .set(TRANSACTION.EXTERNAL_ID, row.externalId)
                    .set(TRANSACTION.TYPE, v.type.code)
                    .set(TRANSACTION.TRANSACTION_DATE, v.date)
                    .set(TRANSACTION.DUE_DATE, v.dueDate)
                    .set(TRANSACTION.DESCRIPTION, v.description)
                    .set(TRANSACTION.AMOUNT_CENTS, v.amountCents)
                    .set(TRANSACTION.CREATED_BATCH_ID, batchId)
                    .set(TRANSACTION.UPDATED_BATCH_ID, batchId)
                    .returning(TRANSACTION.ID).fetchSingle().id,
            )
            post(accountId, id, v.amountCents, "original")
        }

        for (c in plan.toCorrect) {
            val v = c.row.values
            tx.update(TRANSACTION)
                .set(TRANSACTION.TYPE, v.type.code)
                .set(TRANSACTION.TRANSACTION_DATE, v.date)
                .set(TRANSACTION.DUE_DATE, v.dueDate)
                .set(TRANSACTION.DESCRIPTION, v.description)
                .set(TRANSACTION.AMOUNT_CENTS, v.amountCents)
                .set(TRANSACTION.UPDATED_BATCH_ID, batchId)
                .set(TRANSACTION.VERSION, TRANSACTION.VERSION.plus(1))
                .where(TRANSACTION.ID.eq(c.transactionId)).and(TRANSACTION.HOA_ID.eq(hoaId))
                .execute()
            tx.insertInto(TRANSACTION_CORRECTION)
                .set(TRANSACTION_CORRECTION.HOA_ID, hoaId)
                .set(TRANSACTION_CORRECTION.TRANSACTION_ID, c.transactionId)
                .set(TRANSACTION_CORRECTION.BATCH_ID, batchId)
                .set(TRANSACTION_CORRECTION.BEFORE, JSON.valueOf(c.before.toJson().toString()))
                .set(TRANSACTION_CORRECTION.AFTER, JSON.valueOf(v.toJson().toString()))
                .execute()
            // A void posts only the reversal; restoring a voided one only the new amount.
            if (v.amountCents != c.before.amountCents) {
                if (c.before.amountCents != 0L) post(c.accountId, c.transactionId, -c.before.amountCents, "reversal")
                if (v.amountCents != 0L) post(c.accountId, c.transactionId, v.amountCents, "correction")
            }
        }

        // Counts and the checksum only; amounts live in the ledger and corrections tables.
        Audit.record(
            tx, actor,
            AuditEntry(
                action = "finance.transactions_imported",
                hoaId = hoaId,
                targetType = "import_batch",
                targetId = batchId,
                reason = reason,
                details = buildJsonObject {
                    put("fileName", fileName.take(255))
                    put("sha256", checksum)
                    put("rows", plan.rowCount)
                    put("created", plan.toCreate.size)
                    put("corrected", plan.toCorrect.size)
                    put("unchanged", plan.unchanged)
                    put("voided", plan.voids)
                    put("zeroSkipped", plan.zeroSkipped)
                    put("onlinePaymentsMatched", plan.onlinePaymentsMatched)
                },
            ),
        )
        return batchId
    }
}
