package cc.seebrook.identity

import cc.seebrook.db.generated.identity.Tables.EXTERNAL_IDENTITY
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.auth.VerifiedIdentity
import cc.seebrook.platform.db.Database
import java.util.UUID

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
        return when (row.value2()) {
            UserStatus.ACTIVE -> Result.Active(UserActor(requireNotNull(row.value1())))
            else -> Result.Disabled
        }
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
