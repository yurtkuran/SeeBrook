package cc.seebrook.identity

import cc.seebrook.db.generated.identity.Tables.EXTERNAL_IDENTITY
import cc.seebrook.db.generated.identity.Tables.INVITATION
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.platform.auth.VerifiedIdentity
import cc.seebrook.platform.db.Database
import cc.seebrook.property.Ownerships
import org.jooq.DSLContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

internal object InvitationStatus {
    const val PENDING = "pending"
    const val ACCEPTED = "accepted"
    const val REVOKED = "revoked"
}

internal object UserStatus {
    const val ACTIVE = "active"
    const val DISABLED = "disabled"
}

/** Emails are compared case-insensitively by storing them normalized. */
object EmailAddress {
    private val FORMAT = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    /** A plausible address: something@domain.tld, at most 320 characters. Delivery is the real test. */
    fun isValid(normalized: String): Boolean = normalized.length <= 320 && FORMAT.matches(normalized)
}

/**
 * Invitation tokens: 256 random bits, URL-safe. Only the SHA-256 hash is stored, so a database
 * leak does not reveal usable tokens (ED-26). A plain hash suffices because the token is random.
 */
internal object InvitationTokens {
    private val random = SecureRandom()
    private val format = Regex("^[A-Za-z0-9_-]{43}$")

    fun generate(): String = ByteArray(32).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** Null for anything that cannot be a token we issued, without touching the database. */
    fun hashOrNull(token: String): String? =
        token.trim().takeIf(format::matches)
            ?.let { HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
}

internal fun Instant.toDb(): OffsetDateTime = atOffset(ZoneOffset.UTC)

/**
 * Creates an owner invitation for one property and email, revoking any earlier pending one
 * (a resend). The raw token is returned once and never stored.
 *
 * Development and tests only (`dev-invite`): it returns the token instead of emailing it. Operators
 * send invitations with [SendInvitations] / [ResendInvitation], delivered by [DeliverInvitation].
 */
class IssueInvitation(private val db: Database, private val clock: Clock = Clock.systemUTC()) {
    data class Issued(val invitationId: UUID, val token: String, val expiresAt: Instant)

    operator fun invoke(hoaId: UUID, propertyId: UUID, email: String, validFor: Duration = DEFAULT_VALIDITY): Issued {
        val normalized = EmailAddress.normalize(email)
        require(normalized.contains('@')) { "invalid email" }
        val now = clock.instant()
        val token = InvitationTokens.generate()
        val expiresAt = now.plus(validFor)

        val id = db.transaction { tx ->
            tx.update(INVITATION)
                .set(INVITATION.STATUS, InvitationStatus.REVOKED)
                .set(INVITATION.REVOKED_AT, now.toDb())
                .where(INVITATION.PROPERTY_ID.eq(propertyId))
                .and(INVITATION.EMAIL.eq(normalized))
                .and(INVITATION.STATUS.eq(InvitationStatus.PENDING))
                .execute()
            tx.insertInto(INVITATION)
                .set(INVITATION.HOA_ID, hoaId)
                .set(INVITATION.PROPERTY_ID, propertyId)
                .set(INVITATION.EMAIL, normalized)
                .set(INVITATION.TOKEN_HASH, requireNotNull(InvitationTokens.hashOrNull(token)))
                .set(INVITATION.EXPIRES_AT, expiresAt.toDb())
                .set(INVITATION.SENT_AT, now.toDb())
                .returning(INVITATION.ID)
                .fetchSingle()
                .id
        }
        return Issued(requireNotNull(id), token, expiresAt)
    }

    companion object {
        val DEFAULT_VALIDITY: Duration = Duration.ofDays(14)
    }
}

/**
 * Accepts an owner invitation (GA-05) for the signed-in identity, atomically:
 * finds or creates the user, links the sign-in identity, starts the property ownership
 * (via PropertyService) and marks the invitation used.
 *
 * The signed-in account must have a verified email equal to the invited email, so a forwarded or
 * leaked invitation is useless without control of that mailbox (ED-29).
 */
class AcceptInvitation(private val db: Database, private val clock: Clock = Clock.systemUTC()) {
    sealed interface Result {
        data class Accepted(val userId: UUID, val hoaId: UUID, val propertyId: UUID) : Result
        data object NotFound : Result
        data object Expired : Result
        /** Revoked, or already accepted by a different account. */
        data object NoLongerValid : Result
        data object EmailNotVerified : Result
        data object EmailMismatch : Result
        data object AccountDisabled : Result
    }

    operator fun invoke(identity: VerifiedIdentity, token: String): Result {
        val hash = InvitationTokens.hashOrNull(token) ?: return Result.NotFound
        return db.transaction { tx -> accept(tx, identity, hash) }
    }

    private fun accept(tx: DSLContext, identity: VerifiedIdentity, tokenHash: String): Result {
        // Lock the invitation so concurrent attempts are serialized and it can be used only once.
        val invitation = tx.selectFrom(INVITATION)
            .where(INVITATION.TOKEN_HASH.eq(tokenHash))
            .forUpdate()
            .fetchOne() ?: return Result.NotFound
        val hoaId = requireNotNull(invitation.hoaId)
        val propertyId = requireNotNull(invitation.propertyId)
        val linkedUser = findLinkedUser(tx, identity)

        when (invitation.status) {
            // Idempotent retry by the account that accepted it (e.g. the first response was lost).
            InvitationStatus.ACCEPTED ->
                return if (linkedUser != null && invitation.acceptedBy == linkedUser.id) {
                    Result.Accepted(linkedUser.id, hoaId, propertyId)
                } else {
                    Result.NoLongerValid
                }
            InvitationStatus.REVOKED -> return Result.NoLongerValid
        }
        val now = clock.instant()
        // A token match implies the invitation was sent, so it has an expiry.
        val expiresAt = invitation.expiresAt ?: return Result.NotFound
        if (!expiresAt.toInstant().isAfter(now)) return Result.Expired

        val email = identity.email?.let(EmailAddress::normalize)
        if (!identity.emailVerified || email == null) return Result.EmailNotVerified
        if (email != invitation.email) return Result.EmailMismatch

        val user = linkedUser ?: findUserByEmail(tx, email) ?: createUser(tx, email, identity.name ?: invitation.inviteeName)
        if (user.status != UserStatus.ACTIVE) return Result.AccountDisabled
        if (linkedUser == null) {
            tx.insertInto(EXTERNAL_IDENTITY)
                .set(EXTERNAL_IDENTITY.USER_ID, user.id)
                .set(EXTERNAL_IDENTITY.PROVIDER, identity.provider)
                .set(EXTERNAL_IDENTITY.SUBJECT, identity.subject)
                .execute()
        }

        Ownerships.grant(tx, hoaId, propertyId, user.id)
        tx.update(INVITATION)
            .set(INVITATION.STATUS, InvitationStatus.ACCEPTED)
            .set(INVITATION.ACCEPTED_AT, now.toDb())
            .set(INVITATION.ACCEPTED_BY, user.id)
            .where(INVITATION.ID.eq(invitation.id))
            .execute()
        return Result.Accepted(user.id, hoaId, propertyId)
    }

    private data class UserRow(val id: UUID, val status: String)

    private fun findLinkedUser(tx: DSLContext, identity: VerifiedIdentity): UserRow? =
        tx.select(USER_ACCOUNT.ID, USER_ACCOUNT.STATUS)
            .from(USER_ACCOUNT)
            .join(EXTERNAL_IDENTITY).on(EXTERNAL_IDENTITY.USER_ID.eq(USER_ACCOUNT.ID))
            .where(EXTERNAL_IDENTITY.PROVIDER.eq(identity.provider))
            .and(EXTERNAL_IDENTITY.SUBJECT.eq(identity.subject))
            .fetchOne { UserRow(requireNotNull(it.value1()), it.value2()) }

    private fun findUserByEmail(tx: DSLContext, email: String): UserRow? =
        tx.select(USER_ACCOUNT.ID, USER_ACCOUNT.STATUS)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.EMAIL.eq(email))
            .fetchOne { UserRow(requireNotNull(it.value1()), it.value2()) }

    private fun createUser(tx: DSLContext, email: String, name: String?): UserRow {
        val id = tx.insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.EMAIL, email)
            .set(USER_ACCOUNT.DISPLAY_NAME, name?.take(200))
            .returning(USER_ACCOUNT.ID)
            .fetchSingle()
            .id
        return UserRow(requireNotNull(id), UserStatus.ACTIVE)
    }
}
