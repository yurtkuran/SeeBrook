package cc.seebrook.identity

import cc.seebrook.db.generated.identity.Tables.INVITATION
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.email.EmailGateway
import cc.seebrook.platform.email.Recipient
import cc.seebrook.platform.email.RenderedEmail
import cc.seebrook.platform.jobs.Job
import cc.seebrook.platform.jobs.JobHandler
import cc.seebrook.platform.jobs.Jobs
import cc.seebrook.property.Properties
import cc.seebrook.tenant.Hoas
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** The job that emails one invitation (ED-35). Its payload holds only the invitation ID. */
object InvitationEmailJob {
    const val KIND = "identity.send_invitation"

    internal fun key(invitationId: UUID) = "invitation:$invitationId"

    internal fun enqueue(tx: DSLContext, hoaId: UUID, invitationId: UUID): Boolean =
        Jobs.enqueue(tx, KIND, hoaId, key(invitationId), buildJsonObject { put("invitationId", invitationId.toString()) })
}

private fun requireReasonOrNull(reason: String?): String? = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(1000)

/** The invitation for [email] at [unitLabel], or null. */
private fun findPending(tx: DSLContext, hoaId: UUID, unitLabel: String, email: String) =
    Properties.byUnitLabel(tx, hoaId)[unitLabel.trim()]?.let { property ->
        tx.selectFrom(INVITATION)
            .where(INVITATION.HOA_ID.eq(hoaId))
            .and(INVITATION.PROPERTY_ID.eq(property.id))
            .and(INVITATION.EMAIL.eq(EmailAddress.normalize(email)))
            .and(INVITATION.STATUS.eq(InvitationStatus.PENDING))
            .forUpdate()
            .fetchOne()
    }

/**
 * Queues emails for invitations that have never been sent (e.g. after an owner import), for the
 * whole HOA or one unit. Dry run by default; outward-facing, so the operator confirms with commit.
 */
class SendInvitations(private val db: Database) {
    data class Target(val invitationId: UUID, val unitLabel: String, val email: String)

    sealed interface Result {
        data class Planned(val targets: List<Target>) : Result
        data class Queued(val targets: List<Target>) : Result
        data object UnitNotFound : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, unitLabel: String?, commit: Boolean, reason: String? = null): Result {
        if (!actor.can(Permission.MANAGE_MEMBERSHIP, hoaId)) return Result.Forbidden
        return db.transaction { tx ->
            val labels = Properties.byUnitLabel(tx, hoaId).entries.associate { it.value.id to it.key }
            val propertyId = unitLabel?.let { label ->
                labels.entries.firstOrNull { it.value == label.trim() }?.key ?: return@transaction Result.UnitNotFound
            }
            val unsent = tx.select(INVITATION.ID, INVITATION.PROPERTY_ID, INVITATION.EMAIL).from(INVITATION)
                .where(INVITATION.HOA_ID.eq(hoaId))
                .and(INVITATION.STATUS.eq(InvitationStatus.PENDING))
                .and(INVITATION.SENT_AT.isNull)
                .and(if (propertyId != null) INVITATION.PROPERTY_ID.eq(propertyId) else DSL.noCondition())
                .forUpdate()
                .fetch { Target(requireNotNull(it.value1()), labels.getValue(requireNotNull(it.value2())), it.value3()) }
            val alreadyQueued = Jobs.openKeys(tx, unsent.map { InvitationEmailJob.key(it.invitationId) })
            val targets = unsent.filter { InvitationEmailJob.key(it.invitationId) !in alreadyQueued }
                .sortedWith(compareBy({ it.unitLabel }, { it.email }))
            if (!commit) return@transaction Result.Planned(targets)

            targets.forEach { InvitationEmailJob.enqueue(tx, hoaId, it.invitationId) }
            if (targets.isNotEmpty()) {
                Audit.record(
                    tx, actor,
                    AuditEntry(
                        action = "identity.invitations_queued",
                        hoaId = hoaId,
                        targetType = "hoa",
                        targetId = hoaId,
                        reason = requireReasonOrNull(reason),
                        details = buildJsonObject {
                            put("count", targets.size)
                            putJsonArray("invitations") {
                                targets.forEach {
                                    addJsonObject {
                                        put("invitationId", it.invitationId.toString())
                                        put("unit", it.unitLabel)
                                        put("email", it.email)
                                    }
                                }
                            }
                        },
                    ),
                )
            }
            Result.Queued(targets)
        }
    }
}

/**
 * Emails a pending invitation again (lost or expired email). Sending creates a new token, so the
 * earlier link stops working once the new email goes out.
 */
class ResendInvitation(private val db: Database) {
    sealed interface Result {
        data class Queued(val invitationId: UUID) : Result
        data object AlreadyQueued : Result
        /** No pending invitation for that unit and email (never invited, accepted or revoked). */
        data object NotFound : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, unitLabel: String, email: String, reason: String? = null): Result {
        if (!actor.can(Permission.MANAGE_MEMBERSHIP, hoaId)) return Result.Forbidden
        return db.transaction { tx ->
            val invitation = findPending(tx, hoaId, unitLabel, email) ?: return@transaction Result.NotFound
            val id = requireNotNull(invitation.id)
            if (!InvitationEmailJob.enqueue(tx, hoaId, id)) return@transaction Result.AlreadyQueued
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "identity.invitation_resend_queued",
                    hoaId = hoaId,
                    targetType = "invitation",
                    targetId = id,
                    reason = requireReasonOrNull(reason),
                    details = buildJsonObject {
                        put("unit", unitLabel.trim())
                        put("email", invitation.email)
                        put("previouslySent", invitation.sentAt != null)
                    },
                ),
            )
            Result.Queued(id)
        }
    }
}

/** Revokes a pending invitation so its link no longer works, and cancels any queued email. Needs a reason. */
class RevokeInvitation(private val db: Database, private val clock: Clock = Clock.systemUTC()) {
    sealed interface Result {
        data class Revoked(val invitationId: UUID) : Result
        data object NotFound : Result
        data object Forbidden : Result
        data object ReasonRequired : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, unitLabel: String, email: String, reason: String?): Result {
        if (!actor.can(Permission.MANAGE_MEMBERSHIP, hoaId)) return Result.Forbidden
        val why = requireReasonOrNull(reason) ?: return Result.ReasonRequired
        return db.transaction { tx ->
            val invitation = findPending(tx, hoaId, unitLabel, email) ?: return@transaction Result.NotFound
            val id = requireNotNull(invitation.id)
            tx.update(INVITATION)
                .set(INVITATION.STATUS, InvitationStatus.REVOKED)
                .set(INVITATION.REVOKED_AT, clock.instant().toDb())
                .where(INVITATION.ID.eq(id))
                .execute()
            val emailCancelled = Jobs.cancelQueued(tx, InvitationEmailJob.key(id))
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "identity.invitation_revoked",
                    hoaId = hoaId,
                    targetType = "invitation",
                    targetId = id,
                    reason = why,
                    details = buildJsonObject {
                        put("unit", unitLabel.trim())
                        put("email", invitation.email)
                        put("wasSent", invitation.sentAt != null)
                        put("queuedEmailCancelled", emailCancelled)
                    },
                ),
            )
            Result.Revoked(id)
        }
    }
}

/** The HOA's invitations and where each stands, for operators. */
class ListInvitations(private val db: Database, private val clock: Clock = Clock.systemUTC()) {
    enum class State { UNSENT, QUEUED, SENT, EXPIRED, ACCEPTED, REVOKED }

    data class Row(val unitLabel: String, val email: String, val state: State, val sentAt: Instant?, val expiresAt: Instant?)

    sealed interface Result {
        data class Listed(val rows: List<Row>) : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID): Result {
        if (!actor.can(Permission.MANAGE_MEMBERSHIP, hoaId)) return Result.Forbidden
        return db.transaction { tx ->
            val labels = Properties.byUnitLabel(tx, hoaId).entries.associate { it.value.id to it.key }
            val invitations = tx.selectFrom(INVITATION).where(INVITATION.HOA_ID.eq(hoaId)).fetch()
            val queued = Jobs.openKeys(tx, invitations.map { InvitationEmailJob.key(requireNotNull(it.id)) })
            val now = clock.instant()
            val rows = invitations.map { inv ->
                val state = when {
                    inv.status == InvitationStatus.ACCEPTED -> State.ACCEPTED
                    inv.status == InvitationStatus.REVOKED -> State.REVOKED
                    InvitationEmailJob.key(requireNotNull(inv.id)) in queued -> State.QUEUED
                    inv.sentAt == null -> State.UNSENT
                    inv.expiresAt?.toInstant()?.isAfter(now) == true -> State.SENT
                    else -> State.EXPIRED
                }
                Row(labels.getValue(requireNotNull(inv.propertyId)), inv.email, state, inv.sentAt?.toInstant(), inv.expiresAt?.toInstant())
            }
            Result.Listed(rows.sortedWith(compareBy({ it.unitLabel }, { it.email }, { it.sentAt })))
        }
    }
}

/**
 * Sends one invitation email (job handler). In one transaction it checks the invitation is still
 * pending and gives it a fresh token and 14-day expiry; then it sends. If sending fails the job
 * retries, and the retry issues another token, so only the last delivered link works.
 */
class DeliverInvitation(
    private val db: Database,
    private val email: EmailGateway,
    private val linkBase: String,
    private val clock: Clock = Clock.systemUTC(),
) : JobHandler {
    override fun handle(job: Job) {
        val invitationId = UUID.fromString(requireNotNull(job.payload["invitationId"]).jsonPrimitive.content)
        val (recipient, message) = db.transaction { tx -> prepare(tx, invitationId) } ?: return
        email.sendTransactional(recipient, message)
    }

    private fun prepare(tx: DSLContext, invitationId: UUID): Pair<Recipient, RenderedEmail>? {
        val invitation = tx.selectFrom(INVITATION).where(INVITATION.ID.eq(invitationId)).forUpdate().fetchOne()
            ?: return null
        // Accepted or revoked since it was queued: nothing to send.
        if (invitation.status != InvitationStatus.PENDING) return null
        val hoaId = requireNotNull(invitation.hoaId)
        val now = clock.instant()
        val token = InvitationTokens.generate()
        val expiresAt = now.plus(IssueInvitation.DEFAULT_VALIDITY)
        tx.update(INVITATION)
            .set(INVITATION.TOKEN_HASH, requireNotNull(InvitationTokens.hashOrNull(token)))
            .set(INVITATION.SENT_AT, now.toDb())
            .set(INVITATION.EXPIRES_AT, expiresAt.toDb())
            .where(INVITATION.ID.eq(invitationId))
            .execute()
        val hoa = requireNotNull(Hoas.find(tx, hoaId))
        val unit = requireNotNull(Properties.unitLabel(tx, hoaId, requireNotNull(invitation.propertyId)))
        val recipient = Recipient(invitation.email, invitation.inviteeName)
        return recipient to InvitationEmail.compose(
            to = recipient,
            hoaName = hoa.name,
            timeZone = ZoneId.of(hoa.timeZone),
            unitLabel = unit,
            link = "$linkBase#token=$token",
            token = token,
            expiresAt = expiresAt,
        )
    }
}

/** Invitation email template (ED-36): plain text, kept in this codebase, never at the provider. Dates use the HOA's time zone. */
internal object InvitationEmail {
    private val date = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a z", Locale.US)

    fun compose(
        to: Recipient,
        hoaName: String,
        timeZone: ZoneId,
        unitLabel: String,
        link: String,
        token: String,
        expiresAt: Instant,
    ) = RenderedEmail(
        subject = "Your invitation to $hoaName on Seebrook",
        text = """
            |${to.name?.let { "Hello $it," } ?: "Hello,"}
            |
            |$hoaName has invited you to Seebrook, where owners can view HOA documents, their account balance and payments.
            |This invitation is for $unitLabel.
            |
            |To accept, install the Seebrook app, sign in with this email address (${to.email}), and open this link on the same device:
            |$link
            |
            |If the link does not open the app, enter this invitation code in the app instead:
            |$token
            |
            |This invitation expires on ${date.format(expiresAt.atZone(timeZone))} and can be used once. Please do not forward it:
            |it only works for an account with this email address.
            |
            |If you did not expect this email, you can ignore it, or let your HOA board know.
        """.trimMargin(),
    )
}
