package cc.seebrook.identity

import cc.seebrook.db.generated.identity.Tables.ROLE_ASSIGNMENT
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Channel
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

private const val MAX_REASON = 1000

/** Reasons are mandatory for role changes (GA-04 controlled grants); blank or oversized ones are rejected. */
private fun cleanReason(reason: String?): String? = reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_REASON }

private fun findUserId(tx: DSLContext, email: String): UUID? =
    tx.select(USER_ACCOUNT.ID).from(USER_ACCOUNT)
        .where(USER_ACCOUNT.EMAIL.eq(EmailAddress.normalize(email)))
        .fetchOne(USER_ACCOUNT.ID)

/** Who may change [role] in [hoaId]: Root for Root; otherwise Root or a holder of MANAGE_ROLES in that HOA. */
private fun mayManage(actor: UserActor, role: Role, hoaId: UUID?): Boolean =
    if (role == Role.ROOT) actor.isRoot else hoaId != null && actor.can(Permission.MANAGE_ROLES, hoaId)

/** Serializes role changes so "last admin" checks cannot race. */
private fun lockRoles(tx: DSLContext) {
    tx.execute("LOCK TABLE identity.role_assignment IN SHARE ROW EXCLUSIVE MODE")
}

private fun roleDetails(email: String, role: Role, hoaId: UUID?) = buildJsonObject {
    put("userEmail", EmailAddress.normalize(email))
    put("role", role.code)
    hoaId?.let { put("hoaId", it.toString()) }
}

/**
 * Creates the first Root user (ED-31). Works only while no active Root exists, so it cannot be
 * used to add Root later; further Root grants go through [GrantRole] by an existing Root.
 */
class BootstrapRoot(private val db: Database) {
    sealed interface Result {
        data class Created(val userId: UUID) : Result
        data object RootAlreadyExists : Result
    }

    operator fun invoke(email: String): Result {
        val normalized = EmailAddress.normalize(email)
        require(normalized.contains('@')) { "invalid email" }
        return db.transaction { tx ->
            lockRoles(tx)
            val rootExists = tx.fetchExists(
                ROLE_ASSIGNMENT,
                ROLE_ASSIGNMENT.ROLE.eq(Role.ROOT.code).and(ROLE_ASSIGNMENT.REVOKED_AT.isNull),
            )
            if (rootExists) return@transaction Result.RootAlreadyExists

            val userId = findUserId(tx, normalized) ?: requireNotNull(
                tx.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, normalized)
                    .returning(USER_ACCOUNT.ID).fetchSingle().id,
            )
            val assignmentId = tx.insertInto(ROLE_ASSIGNMENT)
                .set(ROLE_ASSIGNMENT.USER_ID, userId)
                .set(ROLE_ASSIGNMENT.ROLE, Role.ROOT.code)
                .returning(ROLE_ASSIGNMENT.ID).fetchSingle().id
            Audit.record(
                tx, actor = null, channel = Channel.CLI,
                AuditEntry(
                    action = "identity.root_bootstrapped",
                    hoaId = null,
                    targetType = "role_assignment",
                    targetId = assignmentId,
                    reason = "Initial platform setup",
                    details = roleDetails(normalized, Role.ROOT, null),
                ),
            )
            Result.Created(userId)
        }
    }
}

/** Grants a role to an existing user (GA-03): only Root grants Root; HOA roles need MANAGE_ROLES in that HOA. */
class GrantRole(private val db: Database) {
    sealed interface Result {
        data class Granted(val assignmentId: UUID) : Result
        data object AlreadyGranted : Result
        data object Forbidden : Result
        data object UserNotFound : Result
        /** Root given an HOA, or an HOA role given none. */
        data object InvalidScope : Result
        data object ReasonRequired : Result
    }

    operator fun invoke(actor: UserActor, userEmail: String, role: Role, hoaId: UUID?, reason: String?): Result {
        if (role.isHoaScoped != (hoaId != null)) return Result.InvalidScope
        if (!mayManage(actor, role, hoaId)) return Result.Forbidden
        val why = cleanReason(reason) ?: return Result.ReasonRequired

        return db.transaction { tx ->
            lockRoles(tx)
            val userId = findUserId(tx, userEmail) ?: return@transaction Result.UserNotFound
            val active = tx.fetchExists(
                ROLE_ASSIGNMENT,
                ROLE_ASSIGNMENT.USER_ID.eq(userId)
                    .and(ROLE_ASSIGNMENT.ROLE.eq(role.code))
                    .and(if (hoaId == null) ROLE_ASSIGNMENT.HOA_ID.isNull else ROLE_ASSIGNMENT.HOA_ID.eq(hoaId))
                    .and(ROLE_ASSIGNMENT.REVOKED_AT.isNull),
            )
            if (active) return@transaction Result.AlreadyGranted

            val assignmentId = requireNotNull(
                tx.insertInto(ROLE_ASSIGNMENT)
                    .set(ROLE_ASSIGNMENT.USER_ID, userId)
                    .set(ROLE_ASSIGNMENT.ROLE, role.code)
                    .set(ROLE_ASSIGNMENT.HOA_ID, hoaId)
                    .set(ROLE_ASSIGNMENT.GRANTED_BY, actor.userId)
                    .returning(ROLE_ASSIGNMENT.ID).fetchSingle().id,
            )
            Audit.record(
                tx, actor,
                AuditEntry("identity.role_granted", hoaId, "role_assignment", assignmentId, why, roleDetails(userEmail, role, hoaId)),
            )
            Result.Granted(assignmentId)
        }
    }
}

/**
 * Revokes an active role (GA-03). Never removes the last active Root (the platform would be
 * unmanageable), and an HOA Admin cannot remove an HOA's last HOA Admin; Root can, with a reason.
 */
class RevokeRole(private val db: Database) {
    sealed interface Result {
        data object Revoked : Result
        data object NotFound : Result
        data object Forbidden : Result
        data object LastRoot : Result
        data object LastHoaAdmin : Result
        data object InvalidScope : Result
        data object ReasonRequired : Result
    }

    operator fun invoke(actor: UserActor, userEmail: String, role: Role, hoaId: UUID?, reason: String?): Result {
        if (role.isHoaScoped != (hoaId != null)) return Result.InvalidScope
        if (!mayManage(actor, role, hoaId)) return Result.Forbidden
        val why = cleanReason(reason) ?: return Result.ReasonRequired

        return db.transaction { tx ->
            lockRoles(tx)
            val userId = findUserId(tx, userEmail) ?: return@transaction Result.NotFound
            val scope = if (hoaId == null) ROLE_ASSIGNMENT.HOA_ID.isNull else ROLE_ASSIGNMENT.HOA_ID.eq(hoaId)
            val activeOfRole = ROLE_ASSIGNMENT.ROLE.eq(role.code).and(scope).and(ROLE_ASSIGNMENT.REVOKED_AT.isNull)
            val assignmentId = tx.select(ROLE_ASSIGNMENT.ID).from(ROLE_ASSIGNMENT)
                .where(activeOfRole).and(ROLE_ASSIGNMENT.USER_ID.eq(userId))
                .fetchOne(ROLE_ASSIGNMENT.ID) ?: return@transaction Result.NotFound

            val holders = tx.fetchCount(ROLE_ASSIGNMENT, activeOfRole)
            if (holders <= 1 && role == Role.ROOT) return@transaction Result.LastRoot
            if (holders <= 1 && role == Role.HOA_ADMIN && !actor.isRoot) return@transaction Result.LastHoaAdmin

            tx.update(ROLE_ASSIGNMENT)
                .set(ROLE_ASSIGNMENT.REVOKED_AT, DSL.currentOffsetDateTime())
                .set(ROLE_ASSIGNMENT.REVOKED_BY, actor.userId)
                .where(ROLE_ASSIGNMENT.ID.eq(assignmentId))
                .execute()
            Audit.record(
                tx, actor,
                AuditEntry("identity.role_revoked", hoaId, "role_assignment", assignmentId, why, roleDetails(userEmail, role, hoaId)),
            )
            Result.Revoked
        }
    }
}
