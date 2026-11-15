package cc.seebrook.identity

import cc.seebrook.db.generated.identity.Tables.INVITATION
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.jobs.Jobs
import cc.seebrook.property.Ownerships
import cc.seebrook.property.Properties
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import java.time.Clock
import java.util.UUID

/**
 * Changes who owns a unit (GA-07, GA-09, ED-37): the unit's owners become exactly the listed
 * people, atomically. Owners not listed lose access at once (their ownership ends, history kept);
 * pending invitations for people not listed are revoked and their queued emails cancelled; people
 * listed who are neither owners nor invited get a new, unsent invitation. Listed people who already
 * own the unit keep their ownership unchanged, so adding or removing a co-owner is also a transfer.
 *
 * Dry run by default; a commit needs a reason and is audited.
 */
class TransferOwnership(private val db: Database, private val clock: Clock = Clock.systemUTC()) {
    data class Plan(
        val unitLabel: String,
        /** Owners whose ownership ends. */
        val ending: List<String>,
        /** Pending invitations revoked. */
        val revoking: List<String>,
        /** New owners to invite. */
        val inviting: List<String>,
        /** Listed people who already own the unit or are invited, unchanged. */
        val continuing: List<String>,
    )

    sealed interface Result {
        data class Planned(val plan: Plan) : Result
        data class Transferred(val plan: Plan) : Result
        data object NoChange : Result
        data object UnitNotFound : Result
        data class InvalidEmail(val email: String) : Result
        data object NoNewOwners : Result
        data object Forbidden : Result
        data object ReasonRequired : Result
    }

    operator fun invoke(
        actor: UserActor,
        hoaId: UUID,
        unitLabel: String,
        newOwnerEmails: List<String>,
        commit: Boolean,
        reason: String? = null,
    ): Result {
        if (!actor.can(Permission.MANAGE_MEMBERSHIP, hoaId)) return Result.Forbidden
        val why = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(1000)
        if (commit && why == null) return Result.ReasonRequired
        val listed = newOwnerEmails.map(EmailAddress::normalize).filter { it.isNotEmpty() }.distinct()
        if (listed.isEmpty()) return Result.NoNewOwners
        listed.firstOrNull { !EmailAddress.isValid(it) }?.let { return Result.InvalidEmail(it) }

        return db.transaction { tx ->
            if (commit) {
                MembershipLock.acquire(tx, hoaId)
            } else {
                tx.execute("SET TRANSACTION READ ONLY")
            }
            val propertyId = Properties.byUnitLabel(tx, hoaId)[unitLabel.trim()]?.id
                ?: return@transaction Result.UnitNotFound
            // Pending invitations first, then owners: an acceptance in progress holds its invitation's
            // lock, so we wait for it and then see its new owner rather than missing it.
            val pending = pendingInvitations(tx, hoaId, propertyId, lock = commit)
            val owners = ownerEmails(tx, Ownerships.activeOwners(tx, hoaId, propertyId, lock = commit))

            val plan = Plan(
                unitLabel = unitLabel.trim(),
                ending = owners.values.filter { it !in listed }.sorted(),
                revoking = pending.values.filter { it !in listed }.sorted(),
                inviting = listed.filter { it !in owners.values && it !in pending.values }.sorted(),
                continuing = listed.filter { it in owners.values || it in pending.values }.sorted(),
            )
            when {
                plan.ending.isEmpty() && plan.revoking.isEmpty() && plan.inviting.isEmpty() -> Result.NoChange
                !commit -> Result.Planned(plan)
                else -> {
                    apply(tx, actor, hoaId, propertyId, plan, owners, pending, requireNotNull(why))
                    Result.Transferred(plan)
                }
            }
        }
    }

    /** Pending invitation emails of the property, keyed by invitation ID. */
    private fun pendingInvitations(tx: DSLContext, hoaId: UUID, propertyId: UUID, lock: Boolean): Map<UUID, String> =
        tx.select(INVITATION.ID, INVITATION.EMAIL).from(INVITATION)
            .where(INVITATION.HOA_ID.eq(hoaId))
            .and(INVITATION.PROPERTY_ID.eq(propertyId))
            .and(INVITATION.STATUS.eq(InvitationStatus.PENDING))
            .let { if (lock) it.forUpdate() else it }
            .fetch()
            .associate { requireNotNull(it.value1()) to it.value2() }

    private fun ownerEmails(tx: DSLContext, userIds: Set<UUID>): Map<UUID, String> =
        if (userIds.isEmpty()) {
            emptyMap()
        } else {
            tx.select(USER_ACCOUNT.ID, USER_ACCOUNT.EMAIL).from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.`in`(userIds))
                .fetch()
                .associate { requireNotNull(it.value1()) to it.value2() }
        }

    private fun apply(
        tx: DSLContext,
        actor: UserActor,
        hoaId: UUID,
        propertyId: UUID,
        plan: Plan,
        owners: Map<UUID, String>,
        pending: Map<UUID, String>,
        reason: String,
    ) {
        val endingUsers = owners.filterValues { it in plan.ending }.keys
        Ownerships.end(tx, hoaId, propertyId, endingUsers)

        val revokedIds = pending.filterValues { it in plan.revoking }.keys
        if (revokedIds.isNotEmpty()) {
            tx.update(INVITATION)
                .set(INVITATION.STATUS, InvitationStatus.REVOKED)
                .set(INVITATION.REVOKED_AT, clock.instant().toDb())
                .where(INVITATION.ID.`in`(revokedIds))
                .execute()
            revokedIds.forEach { Jobs.cancelQueued(tx, InvitationEmailJob.key(it)) }
        }

        val invitedIds = plan.inviting.map { email ->
            requireNotNull(
                tx.insertInto(INVITATION)
                    .set(INVITATION.HOA_ID, hoaId)
                    .set(INVITATION.PROPERTY_ID, propertyId)
                    .set(INVITATION.EMAIL, email)
                    .returning(INVITATION.ID)
                    .fetchSingle()
                    .id,
            )
        }

        fun strings(values: Collection<Any>) = JsonArray(values.map { JsonPrimitive(it.toString()) })
        Audit.record(
            tx, actor,
            AuditEntry(
                action = "identity.ownership_transferred",
                hoaId = hoaId,
                targetType = "property",
                targetId = propertyId,
                reason = reason,
                details = buildJsonObject {
                    put("unit", plan.unitLabel)
                    put("ownersBefore", strings(owners.values.sorted()))
                    put("invitedBefore", strings(pending.values.sorted()))
                    put("endedOwners", strings(plan.ending))
                    put("endedUserIds", strings(endingUsers))
                    put("revokedInvitationIds", strings(revokedIds))
                    put("newInvitations", strings(plan.inviting))
                    put("newInvitationIds", strings(invitedIds))
                    put("continuing", strings(plan.continuing))
                },
            ),
        )
    }
}
