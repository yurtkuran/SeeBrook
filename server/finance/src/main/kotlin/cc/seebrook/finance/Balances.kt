package cc.seebrook.finance

import cc.seebrook.db.generated.finance.Tables.ACCOUNT
import cc.seebrook.db.generated.finance.Tables.LEDGER_ENTRY
import cc.seebrook.db.generated.finance.Tables.TRANSACTION
import cc.seebrook.db.generated.finance.Tables.TRANSACTION_CORRECTION
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.property.Properties
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import java.util.UUID

/** Balances come only from the ledger (GA-21). Positive: owed; negative: a credit. */
internal object Ledger {
    fun balances(tx: DSLContext, hoaId: UUID): Map<UUID, Long> =
        tx.select(LEDGER_ENTRY.ACCOUNT_ID, DSL.sum(LEDGER_ENTRY.AMOUNT_CENTS))
            .from(LEDGER_ENTRY).where(LEDGER_ENTRY.HOA_ID.eq(hoaId))
            .groupBy(LEDGER_ENTRY.ACCOUNT_ID)
            .fetch().associate { requireNotNull(it.value1()) to it.value2().toLong() }

    /** Sum of each account's transactions at their current values. */
    fun transactionTotals(tx: DSLContext, hoaId: UUID): Map<UUID, Long> =
        tx.select(TRANSACTION.ACCOUNT_ID, DSL.sum(TRANSACTION.AMOUNT_CENTS))
            .from(TRANSACTION).where(TRANSACTION.HOA_ID.eq(hoaId))
            .groupBy(TRANSACTION.ACCOUNT_ID)
            .fetch().associate { requireNotNull(it.value1()) to it.value2().toLong() }

    /**
     * The ledger invariant: every account's ledger sum equals the sum of its transactions'
     * current amounts. Returns the accounts where it does not hold (expected: none).
     */
    fun inconsistentAccounts(tx: DSLContext, hoaId: UUID): Set<UUID> {
        val ledger = balances(tx, hoaId)
        val totals = transactionTotals(tx, hoaId)
        return (ledger.keys + totals.keys).filter { (ledger[it] ?: 0L) != (totals[it] ?: 0L) }.toSet()
    }
}

/** Every unit's account and balance, for financial administrators. Unmapped units are listed too. */
class ListBalances(private val db: Database) {
    data class Row(val unitLabel: String, val account: String?, val balanceCents: Long?, val transactions: Int)

    sealed interface Result {
        data class Listed(val rows: List<Row>, val totalCents: Long) : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID): Result {
        if (!actor.can(Permission.MANAGE_FINANCES, hoaId)) return Result.Forbidden
        return db.transaction { tx ->
            val accounts = AccountDirectory.byExternalId(tx, hoaId).values.associateBy { it.propertyId }
            val balances = Ledger.balances(tx, hoaId)
            val counts = tx.select(TRANSACTION.ACCOUNT_ID, DSL.count()).from(TRANSACTION)
                .where(TRANSACTION.HOA_ID.eq(hoaId)).groupBy(TRANSACTION.ACCOUNT_ID)
                .fetch().associate { requireNotNull(it.value1()) to it.value2() }
            val rows = Properties.byUnitLabel(tx, hoaId).entries.sortedBy { it.key }.map { (unit, property) ->
                val account = accounts[property.id]
                Row(unit, account?.externalId, account?.let { balances[it.id] ?: 0L }, account?.let { counts[it.id] } ?: 0)
            }
            Result.Listed(rows, balances.values.sum())
        }
    }
}

/** One unit's transactions at their current values with a running balance, for administrators. */
class AccountStatement(private val db: Database) {
    data class Line(
        val externalId: String,
        val type: TransactionType,
        val date: LocalDate,
        val dueDate: LocalDate?,
        val description: String,
        val amountCents: Long,
        val runningBalanceCents: Long,
        val corrections: Int,
    )

    sealed interface Result {
        data class Statement(val unitLabel: String, val account: String, val lines: List<Line>, val balanceCents: Long) : Result
        data object UnitNotFound : Result
        data object NoAccount : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, unitLabel: String): Result {
        if (!actor.can(Permission.MANAGE_FINANCES, hoaId)) return Result.Forbidden
        return db.transaction { tx ->
            val property = Properties.byUnitLabel(tx, hoaId)[unitLabel] ?: return@transaction Result.UnitNotFound
            val account = tx.selectFrom(ACCOUNT).where(ACCOUNT.HOA_ID.eq(hoaId)).and(ACCOUNT.PROPERTY_ID.eq(property.id)).fetchOne()
                ?: return@transaction Result.NoAccount
            val corrections = DSL.field(
                DSL.selectCount().from(TRANSACTION_CORRECTION).where(TRANSACTION_CORRECTION.TRANSACTION_ID.eq(TRANSACTION.ID)),
            )
            var running = 0L
            val lines = tx.select(TRANSACTION.asterisk(), corrections).from(TRANSACTION)
                .where(TRANSACTION.HOA_ID.eq(hoaId)).and(TRANSACTION.ACCOUNT_ID.eq(account.id))
                .orderBy(TRANSACTION.TRANSACTION_DATE, TRANSACTION.EXTERNAL_ID)
                .fetch { r ->
                    running += r.get(TRANSACTION.AMOUNT_CENTS)
                    Line(
                        r.get(TRANSACTION.EXTERNAL_ID),
                        requireNotNull(TransactionType.fromCode(r.get(TRANSACTION.TYPE))),
                        r.get(TRANSACTION.TRANSACTION_DATE),
                        r.get(TRANSACTION.DUE_DATE),
                        r.get(TRANSACTION.DESCRIPTION),
                        r.get(TRANSACTION.AMOUNT_CENTS),
                        running,
                        r.get(corrections),
                    )
                }
            val balance = Ledger.balances(tx, hoaId)[account.id] ?: 0L
            Result.Statement(unitLabel, account.externalId, lines, balance)
        }
    }
}

/**
 * Compares every account's ledger balance with the balances in the HOA's spreadsheet (file:
 * `account,balance`) and checks the ledger invariant (GA-21, M4 exit criterion). Read-only; the
 * outcome is audited.
 */
class ReconcileBalances(private val db: Database) {
    data class Mismatch(val account: String, val unitLabel: String, val expectedCents: Long, val actualCents: Long)

    data class Report(
        val matched: Int,
        val mismatches: List<Mismatch>,
        /** In the file but not mapped to any unit. */
        val unknownAccounts: List<String>,
        /** Mapped, but missing from the file. */
        val missingAccounts: List<String>,
        /** Accounts whose ledger does not equal their transactions (expected: none). */
        val inconsistentAccounts: List<String>,
    ) {
        val reconciled get() = mismatches.isEmpty() && unknownAccounts.isEmpty() && missingAccounts.isEmpty() && inconsistentAccounts.isEmpty()
    }

    sealed interface Result {
        data class Reconciled(val report: Report) : Result
        data class Invalid(val errors: List<RowError>) : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, fileName: String, content: ByteArray): Result {
        if (!actor.can(Permission.MANAGE_FINANCES, hoaId)) return Result.Forbidden
        val rows = when (val read = FinanceCsv.read(content, listOf("account", "balance"))) {
            is FinanceCsv.Read.Invalid -> return Result.Invalid(read.errors)
            is FinanceCsv.Read.Rows -> read.rows
        }
        val errors = mutableListOf<RowError>()
        val expected = linkedMapOf<String, Long>()
        for (row in rows) {
            val account = row["account"]
            val text = row["balance"]
            val cents = text?.let(Money::parseCents)
            when {
                account == null -> errors += RowError(row.line, "account", "is required")
                text == null -> errors += RowError(row.line, "balance", "is required")
                cents == null -> errors += RowError(row.line, "balance", "'$text' is not an amount in dollars and cents")
                expected.put(account, cents) != null -> errors += RowError(row.line, "account", "repeats an earlier line")
            }
        }
        if (errors.isNotEmpty()) return Result.Invalid(errors)

        return db.transaction { tx ->
            val accounts = AccountDirectory.byExternalId(tx, hoaId)
            val units = Properties.byUnitLabel(tx, hoaId).entries.associate { (label, p) -> p.id to label }
            val balances = Ledger.balances(tx, hoaId)
            val mismatches = expected.mapNotNull { (externalId, cents) ->
                val account = accounts[externalId] ?: return@mapNotNull null
                val actual = balances[account.id] ?: 0L
                if (actual == cents) null else Mismatch(externalId, units[account.propertyId].orEmpty(), cents, actual)
            }
            val inconsistent = Ledger.inconsistentAccounts(tx, hoaId)
            val report = Report(
                matched = expected.keys.count { it in accounts } - mismatches.size,
                mismatches = mismatches,
                unknownAccounts = expected.keys.filter { it !in accounts },
                missingAccounts = accounts.keys.filter { it !in expected }.sorted(),
                inconsistentAccounts = accounts.values.filter { it.id in inconsistent }.map { it.externalId }.sorted(),
            )
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "finance.balances_reconciled",
                    hoaId = hoaId,
                    targetType = "hoa",
                    targetId = hoaId,
                    details = buildJsonObject {
                        put("fileName", fileName.take(255))
                        put("reconciled", report.reconciled)
                        put("matched", report.matched)
                        put("mismatched", report.mismatches.size)
                        put("unknownAccounts", report.unknownAccounts.size)
                        put("missingAccounts", report.missingAccounts.size)
                        put("inconsistentAccounts", report.inconsistentAccounts.size)
                    },
                ),
            )
            Result.Reconciled(report)
        }
    }
}
