package cc.seebrook.finance

import cc.seebrook.db.generated.finance.Tables.ACCOUNT
import cc.seebrook.db.generated.finance.Tables.TRANSACTION
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.property.Properties
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import java.util.UUID

/**
 * Serializes finance changes within one HOA for the rest of the transaction, so each import plans
 * against data no other finance change is modifying (GA-21).
 */
internal object FinanceLock {
    fun acquire(tx: DSLContext, hoaId: UUID) {
        tx.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "finance:$hoaId")
    }
}

internal fun reasonOf(reason: String?): String? = reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= 1000 }

/** Spreadsheet identifiers: 1 to 64 characters, no control characters. */
internal fun validIdentifier(value: String) = value.length <= 64 && value.none { it.isISOControl() }

data class AccountRef(val id: UUID, val propertyId: UUID, val externalId: String)

internal object AccountDirectory {
    fun byExternalId(tx: DSLContext, hoaId: UUID): Map<String, AccountRef> =
        tx.select(ACCOUNT.ID, ACCOUNT.PROPERTY_ID, ACCOUNT.EXTERNAL_ID).from(ACCOUNT)
            .where(ACCOUNT.HOA_ID.eq(hoaId))
            .fetch { AccountRef(requireNotNull(it.value1()), requireNotNull(it.value2()), it.value3()) }
            .associateBy { it.externalId }
}

/**
 * Maps each property to its account identifier in the HOA's spreadsheet (GA-20, ED-41). The file
 * has `unit_label,account`. Accounts are created once per property and never move to another
 * property. An identifier can be corrected only while the account has no transactions; after
 * that the mapping is fixed, because imported history depends on it.
 */
class ImportAccounts(private val db: Database) {
    data class Mapping(val unitLabel: String, val externalId: String)
    data class Remap(val unitLabel: String, val from: String, val to: String)

    data class Plan(val toCreate: List<Mapping>, val toRemap: List<Remap>, val unchanged: Int) {
        val isEmpty get() = toCreate.isEmpty() && toRemap.isEmpty()
    }

    sealed interface Result {
        data class Invalid(val errors: List<RowError>) : Result
        data class Conflicts(val conflicts: List<String>) : Result
        data class Planned(val plan: Plan) : Result
        data class Imported(val plan: Plan) : Result
        data object Forbidden : Result
        data object ReasonRequired : Result
    }

    private data class Row(val line: Long, val unitLabel: String, val externalId: String)

    operator fun invoke(actor: UserActor, hoaId: UUID, content: ByteArray, commit: Boolean, reason: String? = null): Result {
        if (!actor.can(Permission.MANAGE_FINANCES, hoaId)) return Result.Forbidden
        val why = reasonOf(reason)
        if (commit && why == null) return Result.ReasonRequired
        val errors = mutableListOf<RowError>()
        val rows = parse(content, errors)
        if (errors.isNotEmpty()) return Result.Invalid(errors.sortedBy { it.line })
        return db.transaction { tx ->
            FinanceLock.acquire(tx, hoaId)
            val outcome = plan(tx, hoaId, rows)
            if (outcome is Result.Planned && commit) {
                apply(tx, actor, hoaId, outcome.plan, requireNotNull(why))
                Result.Imported(outcome.plan)
            } else {
                outcome
            }
        }
    }

    /** Every problem goes into [errors]; the rows are usable only when it stays empty. */
    private fun parse(content: ByteArray, errors: MutableList<RowError>): List<Row> {
        val rows = when (val read = FinanceCsv.read(content, listOf("unit_label", "account"))) {
            is FinanceCsv.Read.Invalid -> return emptyList<Row>().also { errors += read.errors }
            is FinanceCsv.Read.Rows -> read.rows
        }
        val parsed = rows.mapNotNull { row ->
            val unit = row["unit_label"]
            val account = row["account"]
            if (unit == null) errors += RowError(row.line, "unit_label", "is required")
            if (account == null) errors += RowError(row.line, "account", "is required")
            if (account != null && !validIdentifier(account)) errors += RowError(row.line, "account", "is longer than 64 characters or has control characters")
            if (unit != null && account != null && validIdentifier(account)) Row(row.line, unit, account) else null
        }
        for (column in listOf("unit_label", "account")) {
            rows.filter { it[column] != null }.groupBy { it[column] }.values.filter { it.size > 1 }.forEach { dupes ->
                dupes.drop(1).forEach { errors += RowError(it.line, column, "repeats line ${dupes.first().line}") }
            }
        }
        return parsed
    }

    private fun plan(tx: DSLContext, hoaId: UUID, rows: List<Row>): Result {
        val properties = Properties.byUnitLabel(tx, hoaId)
        val accounts = AccountDirectory.byExternalId(tx, hoaId)
        val byProperty = accounts.values.associateBy { it.propertyId }
        val conflicts = mutableListOf<String>()
        val toCreate = mutableListOf<Mapping>()
        val toRemap = mutableListOf<Remap>()
        var unchanged = 0

        for (row in rows) {
            val property = properties[row.unitLabel]
            if (property == null) {
                conflicts += "Line ${row.line}: no unit '${row.unitLabel}' in this HOA"
                continue
            }
            val current = byProperty[property.id]
            val owner = accounts[row.externalId]
            when {
                current?.externalId == row.externalId -> unchanged++
                owner != null -> conflicts += "Line ${row.line}: account '${row.externalId}' already belongs to another unit"
                current == null -> toCreate += Mapping(row.unitLabel, row.externalId)
                hasTransactions(tx, current.id) ->
                    conflicts += "Line ${row.line}: ${row.unitLabel} is mapped to '${current.externalId}', which has transactions; it cannot change"
                else -> toRemap += Remap(row.unitLabel, current.externalId, row.externalId)
            }
        }
        return if (conflicts.isEmpty()) Result.Planned(Plan(toCreate, toRemap, unchanged)) else Result.Conflicts(conflicts)
    }

    private fun hasTransactions(tx: DSLContext, accountId: UUID) = tx.fetchExists(TRANSACTION, TRANSACTION.ACCOUNT_ID.eq(accountId))

    private fun apply(tx: DSLContext, actor: UserActor, hoaId: UUID, plan: Plan, reason: String) {
        if (plan.isEmpty) return
        val properties = Properties.byUnitLabel(tx, hoaId)
        plan.toCreate.forEach {
            tx.insertInto(ACCOUNT)
                .set(ACCOUNT.HOA_ID, hoaId)
                .set(ACCOUNT.PROPERTY_ID, properties.getValue(it.unitLabel).id)
                .set(ACCOUNT.EXTERNAL_ID, it.externalId)
                .execute()
        }
        plan.toRemap.forEach {
            tx.update(ACCOUNT).set(ACCOUNT.EXTERNAL_ID, it.to)
                .where(ACCOUNT.HOA_ID.eq(hoaId)).and(ACCOUNT.PROPERTY_ID.eq(properties.getValue(it.unitLabel).id))
                .execute()
        }
        Audit.record(
            tx, actor,
            AuditEntry(
                action = "finance.accounts_mapped",
                hoaId = hoaId,
                targetType = "hoa",
                targetId = hoaId,
                reason = reason,
                details = buildJsonObject {
                    put("created", JsonArray(plan.toCreate.map { JsonPrimitive("${it.unitLabel}=${it.externalId}") }))
                    put("remapped", JsonArray(plan.toRemap.map { JsonPrimitive("${it.unitLabel}: ${it.from} -> ${it.to}") }))
                },
            ),
        )
    }
}
