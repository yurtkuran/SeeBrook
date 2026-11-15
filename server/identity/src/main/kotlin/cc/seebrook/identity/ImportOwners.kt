package cc.seebrook.identity

import cc.seebrook.db.generated.identity.Tables.INVITATION
import cc.seebrook.db.generated.identity.Tables.OWNER_IMPORT
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.property.Properties
import cc.seebrook.property.PropertyInput
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jooq.DSLContext
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * Imports HOA-verified owners from CSV (GA-05, GA-14, ED-34): creates missing properties and an
 * unsent invitation for each owner. Additive and conservative:
 *  - the whole file is validated and planned before anything is written; any error or conflict
 *    rejects it entirely;
 *  - a unit that already has owners or invitations must list exactly those owners, otherwise it
 *    is a conflict — ownership changes go through TransferOwnership, never an import;
 *  - an existing unit's details must match; units missing from the file are left alone;
 *  - re-running the same file, or any file whose changes are already applied, changes nothing.
 */
class ImportOwners(private val db: Database) {
    data class PlannedInvitation(val unitLabel: String, val email: String, val name: String?)

    data class Plan(
        val rowCount: Int,
        val propertiesToCreate: List<PropertyInput>,
        val invitationsToCreate: List<PlannedInvitation>,
        val unchangedUnits: Int,
    ) {
        val isEmpty get() = propertiesToCreate.isEmpty() && invitationsToCreate.isEmpty()
    }

    data class Conflict(val unitLabel: String, val message: String)

    sealed interface Result {
        data class Invalid(val errors: List<RowError>) : Result
        data class Conflicts(val conflicts: List<Conflict>) : Result
        /** Dry run: what a commit would do. */
        data class Planned(val plan: Plan) : Result
        data class Imported(val importId: UUID, val plan: Plan) : Result
        data class AlreadyImported(val importId: UUID, val importedAt: Instant) : Result
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
        if (!actor.can(Permission.MANAGE_MEMBERSHIP, hoaId)) return Result.Forbidden
        val why = reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= 1000 }
        if (commit && why == null) return Result.ReasonRequired

        val rows = when (val parsed = OwnerCsv.parse(content)) {
            is OwnerCsv.Parsed.Invalid -> return Result.Invalid(parsed.errors)
            is OwnerCsv.Parsed.Valid -> parsed.rows
        }
        val checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))

        if (!commit) {
            return db.transaction { tx ->
                tx.setReadOnly()
                plan(tx, hoaId, rows).fold({ Result.Conflicts(it) }, { Result.Planned(it) })
            }
        }
        return db.transaction { tx ->
            // One import per HOA at a time, so the plan cannot go stale before it is applied.
            tx.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "owner-import:$hoaId")
            previousImport(tx, hoaId, checksum)?.let { return@transaction it }
            plan(tx, hoaId, rows).fold({ Result.Conflicts(it) }) { plan ->
                val importId = apply(tx, actor, hoaId, fileName, checksum, plan)
                Audit.record(tx, actor, auditEntry(hoaId, importId, fileName, checksum, plan, requireNotNull(why)))
                Result.Imported(importId, plan)
            }
        }
    }

    private fun DSLContext.setReadOnly() {
        execute("SET TRANSACTION READ ONLY")
    }

    private fun previousImport(tx: DSLContext, hoaId: UUID, checksum: String): Result.AlreadyImported? =
        tx.select(OWNER_IMPORT.ID, OWNER_IMPORT.IMPORTED_AT).from(OWNER_IMPORT)
            .where(OWNER_IMPORT.HOA_ID.eq(hoaId)).and(OWNER_IMPORT.FILE_SHA256.eq(checksum))
            .fetchOne { Result.AlreadyImported(requireNotNull(it.value1()), it.value2().toInstant()) }

    private sealed interface Outcome {
        data class Planned(val plan: Plan) : Outcome
        data class Rejected(val conflicts: List<Conflict>) : Outcome
        fun <T> fold(onConflicts: (List<Conflict>) -> T, onPlan: (Plan) -> T): T = when (this) {
            is Planned -> onPlan(plan)
            is Rejected -> onConflicts(conflicts)
        }
    }

    private fun plan(tx: DSLContext, hoaId: UUID, rows: List<OwnerRow>): Outcome {
        val existing = Properties.byUnitLabel(tx, hoaId)
        val owners = currentOwnerEmails(tx, hoaId)
        val invited = pendingInvitationEmails(tx, hoaId)

        val conflicts = mutableListOf<Conflict>()
        val toCreate = mutableListOf<PropertyInput>()
        val toInvite = mutableListOf<PlannedInvitation>()
        var unchanged = 0

        for ((unit, unitRows) in rows.groupBy { it.property.unitLabel }) {
            val input = unitRows.first().property
            val fileOwners = unitRows.mapNotNull { row -> row.email?.let { it to row.name } }.toMap()
            val property = existing[unit]
            if (property == null) {
                toCreate += input
                fileOwners.forEach { (email, name) -> toInvite += PlannedInvitation(unit, email, name) }
                continue
            }
            if (property.input != input) {
                conflicts += Conflict(unit, "details differ from the existing unit (${differences(property.input, input)})")
                continue
            }
            val current = owners[property.id].orEmpty() + invited[property.id].orEmpty()
            when {
                fileOwners.isEmpty() || fileOwners.keys == current -> unchanged++
                current.isEmpty() -> fileOwners.forEach { (email, name) -> toInvite += PlannedInvitation(unit, email, name) }
                else -> conflicts += Conflict(
                    unit,
                    "already has owners or invitations (${current.sorted().joinToString()}) that differ from the file " +
                        "(${fileOwners.keys.sorted().joinToString()}); change ownership with a transfer instead",
                )
            }
        }
        return if (conflicts.isNotEmpty()) {
            Outcome.Rejected(conflicts.sortedBy { it.unitLabel })
        } else {
            Outcome.Planned(Plan(rows.size, toCreate, toInvite, unchanged))
        }
    }

    private fun differences(a: PropertyInput, b: PropertyInput): String = listOfNotNull(
        "address_line1".takeIf { a.addressLine1 != b.addressLine1 },
        "address_line2".takeIf { a.addressLine2 != b.addressLine2 },
        "city".takeIf { a.city != b.city },
        "state_code".takeIf { a.stateCode != b.stateCode },
        "postal_code".takeIf { a.postalCode != b.postalCode },
    ).joinToString()

    /** Emails of each property's active owners. */
    private fun currentOwnerEmails(tx: DSLContext, hoaId: UUID): Map<UUID, Set<String>> {
        val owners = Properties.activeOwners(tx, hoaId)
        val userIds = owners.values.flatten().toSet()
        if (userIds.isEmpty()) return emptyMap()
        val emails = tx.select(USER_ACCOUNT.ID, USER_ACCOUNT.EMAIL).from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.`in`(userIds))
            .fetchMap(USER_ACCOUNT.ID, USER_ACCOUNT.EMAIL)
        return owners.mapValues { (_, ids) -> ids.mapNotNull { emails[it] }.toSet() }
    }

    /** Emails of each property's pending invitations, sent or not. */
    private fun pendingInvitationEmails(tx: DSLContext, hoaId: UUID): Map<UUID, Set<String>> =
        tx.select(INVITATION.PROPERTY_ID, INVITATION.EMAIL).from(INVITATION)
            .where(INVITATION.HOA_ID.eq(hoaId)).and(INVITATION.STATUS.eq(InvitationStatus.PENDING))
            .fetch()
            .groupBy({ requireNotNull(it.value1()) }, { it.value2() })
            .mapValues { it.value.toSet() }

    private fun apply(
        tx: DSLContext,
        actor: UserActor,
        hoaId: UUID,
        fileName: String,
        checksum: String,
        plan: Plan,
    ): UUID {
        val importId = requireNotNull(
            tx.insertInto(OWNER_IMPORT)
                .set(OWNER_IMPORT.HOA_ID, hoaId)
                .set(OWNER_IMPORT.FILE_SHA256, checksum)
                .set(OWNER_IMPORT.FILE_NAME, fileName.take(255))
                .set(OWNER_IMPORT.ROW_COUNT, plan.rowCount)
                .set(OWNER_IMPORT.PROPERTIES_CREATED, plan.propertiesToCreate.size)
                .set(OWNER_IMPORT.INVITATIONS_CREATED, plan.invitationsToCreate.size)
                .set(OWNER_IMPORT.IMPORTED_BY, actor.userId)
                .returning(OWNER_IMPORT.ID)
                .fetchSingle()
                .id,
        )
        plan.propertiesToCreate.forEach { Properties.create(tx, hoaId, it) }
        val propertyIds = Properties.byUnitLabel(tx, hoaId).mapValues { it.value.id }
        plan.invitationsToCreate.forEach { invitation ->
            tx.insertInto(INVITATION)
                .set(INVITATION.HOA_ID, hoaId)
                .set(INVITATION.PROPERTY_ID, propertyIds.getValue(invitation.unitLabel))
                .set(INVITATION.EMAIL, invitation.email)
                .set(INVITATION.INVITEE_NAME, invitation.name)
                .set(INVITATION.OWNER_IMPORT_ID, importId)
                .execute()
        }
        return importId
    }

    private fun auditEntry(hoaId: UUID, importId: UUID, fileName: String, checksum: String, plan: Plan, reason: String) =
        AuditEntry(
            action = "identity.owners_imported",
            hoaId = hoaId,
            targetType = "owner_import",
            targetId = importId,
            reason = reason,
            details = buildJsonObject {
                put("fileName", fileName.take(255))
                put("fileSha256", checksum)
                put("rows", plan.rowCount)
                put("unchangedUnits", plan.unchangedUnits)
                putJsonArray("propertiesCreated") { plan.propertiesToCreate.forEach { add(it.unitLabel) } }
                putJsonArray("invitationsCreated") {
                    plan.invitationsToCreate.forEach {
                        addJsonObject {
                            put("unit", it.unitLabel)
                            put("email", it.email)
                        }
                    }
                }
            },
        )
}
