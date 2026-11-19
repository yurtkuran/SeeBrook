package cc.seebrook.identity

import cc.seebrook.db.generated.identity.Tables.EXTERNAL_IDENTITY
import cc.seebrook.db.generated.identity.Tables.ROLE_ASSIGNMENT
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.platform.auth.Channel
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.RoleGrant
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.auth.VerifiedIdentity
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.email.Recipient
import org.jooq.DSLContext
import java.util.UUID

/** Active role assignments of [userId], read fresh for each request or command. */
internal fun activeRoles(dsl: DSLContext, userId: UUID): Set<RoleGrant> =
    dsl.select(ROLE_ASSIGNMENT.ROLE, ROLE_ASSIGNMENT.HOA_ID)
        .from(ROLE_ASSIGNMENT)
        .where(ROLE_ASSIGNMENT.USER_ID.eq(userId))
        .and(ROLE_ASSIGNMENT.REVOKED_AT.isNull)
        .fetch { row -> Role.fromCode(row.value1())?.let { RoleGrant(it, row.value2()) } }
        .filterNotNull()
        .toSet()

/**
 * Maps a verified sign-in identity to our user on every request (ED-28). A disabled user is
 * refused immediately, whatever the remaining lifetime of their token.
 */
class ResolveUser(private val db: Database) {
    sealed interface Result {
        data class Active(val actor: UserActor) : Result
        /** Signed in, but has not accepted an invitation yet. */
        data object NotRegistered : Result
        data object Disabled : Result
    }

    operator fun invoke(identity: VerifiedIdentity): Result {
        val row = db.dsl.select(USER_ACCOUNT.ID, USER_ACCOUNT.STATUS)
            .from(USER_ACCOUNT)
            .join(EXTERNAL_IDENTITY).on(EXTERNAL_IDENTITY.USER_ID.eq(USER_ACCOUNT.ID))
            .where(EXTERNAL_IDENTITY.PROVIDER.eq(identity.provider))
            .and(EXTERNAL_IDENTITY.SUBJECT.eq(identity.subject))
            .fetchOne() ?: return Result.NotRegistered
        if (row.value2() != UserStatus.ACTIVE) return Result.Disabled
        val userId = requireNotNull(row.value1())
        return Result.Active(UserActor(userId, activeRoles(db.dsl, userId), Channel.API))
    }
}

/**
 * Identifies the operator running an admin command (ED-31). The operator names themselves by
 * email; what they may do is decided by their roles in our database, exactly as for the API.
 * Shell access to the environment is the outer control.
 */
class ResolveOperator(private val db: Database) {
    sealed interface Result {
        data class Active(val actor: UserActor) : Result
        data object NotFound : Result
        data object Disabled : Result
    }

    operator fun invoke(email: String): Result {
        val row = db.dsl.select(USER_ACCOUNT.ID, USER_ACCOUNT.STATUS)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.EMAIL.eq(EmailAddress.normalize(email)))
            .fetchOne() ?: return Result.NotFound
        if (row.value2() != UserStatus.ACTIVE) return Result.Disabled
        val userId = requireNotNull(row.value1())
        return Result.Active(UserActor(userId, activeRoles(db.dsl, userId), Channel.CLI))
    }
}

data class MeView(val id: UUID, val email: String, val displayName: String?)

class GetMe(private val db: Database) {
    operator fun invoke(actor: UserActor): MeView =
        db.dsl.select(USER_ACCOUNT.ID, USER_ACCOUNT.EMAIL, USER_ACCOUNT.DISPLAY_NAME)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(actor.userId))
            .fetchSingle { MeView(requireNotNull(it.value1()), it.value2(), it.value3()) }
}

/**
 * A user's email address and name, for emails about their own actions (receipts, ED-45). Null
 * for a disabled or unknown user: nothing is sent.
 */
class UserContacts(private val db: Database) {
    fun find(userId: UUID): Recipient? =
        db.dsl.select(USER_ACCOUNT.EMAIL, USER_ACCOUNT.DISPLAY_NAME, USER_ACCOUNT.STATUS).from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(userId))
            .fetchOne()
            ?.takeIf { it.value3() == "active" }
            ?.let { Recipient(it.value1(), it.value2()) }
}
