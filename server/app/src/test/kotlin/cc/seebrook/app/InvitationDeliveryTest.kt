package cc.seebrook.app

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.db.generated.identity.Tables.INVITATION
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.db.generated.jobs.Tables.OUTBOX_JOB
import cc.seebrook.identity.AcceptInvitation
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.DeliverInvitation
import cc.seebrook.identity.GrantRole
import cc.seebrook.identity.ImportOwners
import cc.seebrook.identity.InvitationEmailJob
import cc.seebrook.identity.ListInvitations
import cc.seebrook.identity.ResendInvitation
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.identity.RevokeInvitation
import cc.seebrook.identity.SendInvitations
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.email.BulkResult
import cc.seebrook.platform.email.EmailGateway
import cc.seebrook.platform.email.EmailTemplate
import cc.seebrook.platform.email.Recipient
import cc.seebrook.platform.email.RenderedEmail
import cc.seebrook.platform.jobs.JobRunner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Sending, resending and revoking owner invitations through the job outbox (GA-05, ED-35). */
class InvitationDeliveryTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val hoa: UUID = Fixtures.hoa(db, "hoa-a")
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private data class Sent(val to: String, val name: String?, val subject: String, val text: String, val email: RenderedEmail)
    private val sent = mutableListOf<Sent>()
    private var failNextSend = false
    private val gateway = object : EmailGateway {
        override fun sendTransactional(to: Recipient, email: RenderedEmail) {
            if (failNextSend) {
                failNextSend = false
                throw IllegalStateException("provider unavailable")
            }
            sent += Sent(to.email, to.name, email.subject, email.text, email)
        }

        override fun sendBulk(hoaId: UUID, recipients: List<Recipient>, template: EmailTemplate): BulkResult =
            error("invitations are transactional")
    }
    private val jobs = JobRunner(
        db, mapOf(InvitationEmailJob.KIND to DeliverInvitation(db, gateway, "https://seebrook.cc/invite")),
    )

    init {
        val csv = """
            unit_label,address_line1,address_line2,city,state_code,postal_code,owner_email,owner_name
            Unit 1,1 Harbor Dr,,Ocean Isle Beach,NC,28469,owner1@example.test,Pat Owner
            Unit 2,2 Harbor Dr,,Ocean Isle Beach,NC,28469,owner2@example.test,
        """.trimIndent().toByteArray()
        ImportOwners(db)(root, hoa, "owners.csv", csv, commit = true, reason = "Roster")
            .shouldBeInstanceOf<ImportOwners.Result.Imported>()
    }

    @AfterEach
    fun close() = db.close()

    private fun sendAll() = SendInvitations(db)(root, hoa, null, commit = true)
    private fun tokenIn(message: Sent) =
        requireNotNull(Regex("#token=([A-Za-z0-9_-]{43})").find(message.text)).groupValues[1]
    private fun accept(email: String, token: String) =
        AcceptInvitation(db)(TestAuth.identity("uid-$email", email), token)
    private fun jobStatuses() = db.dsl.select(OUTBOX_JOB.STATUS).from(OUTBOX_JOB).fetch(OUTBOX_JOB.STATUS)

    @Test
    fun `dry run lists unsent invitations and queues nothing`() {
        val plan = SendInvitations(db)(root, hoa, null, commit = false)

        plan.shouldBeInstanceOf<SendInvitations.Result.Planned>().targets.map { it.email } shouldBe
            listOf("owner1@example.test", "owner2@example.test")
        db.dsl.fetchCount(OUTBOX_JOB) shouldBe 0
    }

    @Test
    fun `queued invitations are emailed with a working link, once`() {
        sendAll().shouldBeInstanceOf<SendInvitations.Result.Queued>().targets.size shouldBe 2
        sendAll().shouldBeInstanceOf<SendInvitations.Result.Queued>().targets.shouldBeEmpty() // already queued
        db.dsl.fetchCount(EVENT, EVENT.ACTION.eq("identity.invitations_queued")) shouldBe 1

        jobs.drain() shouldBe 2
        sent.map { it.to } shouldBe listOf("owner1@example.test", "owner2@example.test")
        val first = sent.first()
        first.subject shouldContain "hoa-a"
        first.text shouldContain "Hello Pat Owner,"
        first.text shouldContain "Unit 1"
        first.email.toString() shouldNotContain tokenIn(first)

        accept("owner1@example.test", tokenIn(first)).shouldBeInstanceOf<AcceptInvitation.Result.Accepted>()
        // Sent invitations are not sent again by send-invitations.
        SendInvitations(db)(root, hoa, null, commit = false)
            .shouldBeInstanceOf<SendInvitations.Result.Planned>().targets.shouldBeEmpty()
    }

    @Test
    fun `the job payload holds no personal data or token`() {
        sendAll()
        db.dsl.select(OUTBOX_JOB.PAYLOAD).from(OUTBOX_JOB).fetch(OUTBOX_JOB.PAYLOAD).forEach {
            it.data() shouldNotContain "@"
            it.data() shouldContain "invitationId"
        }
    }

    @Test
    fun `resending issues a new link and the old one stops working`() {
        sendAll(); jobs.drain()
        val oldToken = tokenIn(sent.first { it.to == "owner1@example.test" })

        ResendInvitation(db)(root, hoa, "Unit 1", "OWNER1@example.test")
            .shouldBeInstanceOf<ResendInvitation.Result.Queued>()
        ResendInvitation(db)(root, hoa, "Unit 1", "owner1@example.test") shouldBe ResendInvitation.Result.AlreadyQueued
        jobs.drain() shouldBe 1
        val newToken = tokenIn(sent.last())

        newToken shouldNotBe oldToken
        accept("owner1@example.test", oldToken) shouldBe AcceptInvitation.Result.NotFound
        accept("owner1@example.test", newToken).shouldBeInstanceOf<AcceptInvitation.Result.Accepted>()
        ResendInvitation(db)(root, hoa, "Unit 1", "owner1@example.test") shouldBe ResendInvitation.Result.NotFound
    }

    @Test
    fun `revoking cancels a queued email and disables a sent link`() {
        RevokeInvitation(db)(root, hoa, "Unit 1", "owner1@example.test", " ") shouldBe RevokeInvitation.Result.ReasonRequired

        sendAll()
        RevokeInvitation(db)(root, hoa, "Unit 1", "owner1@example.test", "Sold before onboarding")
            .shouldBeInstanceOf<RevokeInvitation.Result.Revoked>()
        jobs.drain() shouldBe 1
        sent.map { it.to } shouldBe listOf("owner2@example.test")
        jobStatuses().sorted() shouldBe listOf("cancelled", "done")

        RevokeInvitation(db)(root, hoa, "Unit 2", "owner2@example.test", "Wrong email on file")
            .shouldBeInstanceOf<RevokeInvitation.Result.Revoked>()
        accept("owner2@example.test", tokenIn(sent.single())) shouldBe AcceptInvitation.Result.NoLongerValid
        db.dsl.fetchCount(EVENT, EVENT.ACTION.eq("identity.invitation_revoked")) shouldBe 2
    }

    @Test
    fun `a failed send is retried with a fresh token`() {
        SendInvitations(db)(root, hoa, "Unit 1", commit = true)
        failNextSend = true

        jobs.drain() shouldBe 1
        sent.shouldBeEmpty()
        val hashAfterFailure = db.dsl.select(INVITATION.TOKEN_HASH).from(INVITATION)
            .where(INVITATION.EMAIL.eq("owner1@example.test")).fetchSingle(INVITATION.TOKEN_HASH)
        jobStatuses() shouldBe listOf("queued")

        db.dsl.update(OUTBOX_JOB).set(OUTBOX_JOB.RUN_AFTER, DSL.currentOffsetDateTime()).execute()
        jobs.drain() shouldBe 1
        val token = tokenIn(sent.single())
        db.dsl.select(INVITATION.TOKEN_HASH).from(INVITATION)
            .where(INVITATION.EMAIL.eq("owner1@example.test")).fetchSingle(INVITATION.TOKEN_HASH) shouldNotBe hashAfterFailure
        accept("owner1@example.test", token).shouldBeInstanceOf<AcceptInvitation.Result.Accepted>()
    }

    @Test
    fun `the list shows each invitation's state`() {
        SendInvitations(db)(root, hoa, "Unit 1", commit = true)
        jobs.drain()
        SendInvitations(db)(root, hoa, "Unit 2", commit = true)

        val rows = ListInvitations(db)(root, hoa).shouldBeInstanceOf<ListInvitations.Result.Listed>().rows
        rows.map { it.unitLabel to it.state } shouldBe listOf(
            "Unit 1" to ListInvitations.State.SENT,
            "Unit 2" to ListInvitations.State.QUEUED,
        )
        SendInvitations(db)(root, hoa, "Unit 9", commit = true) shouldBe SendInvitations.Result.UnitNotFound
    }

    @Test
    fun `only operators who manage membership in the HOA may send, resend, revoke or list`() {
        val otherHoa = Fixtures.hoa(db, "hoa-b")
        db.dsl.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, "member@example.test").execute()
        GrantRole(db)(root, "member@example.test", Role.MEMBERSHIP_ADMIN, otherHoa, "r")
        val outsider = (ResolveOperator(db)("member@example.test") as ResolveOperator.Result.Active).actor

        SendInvitations(db)(outsider, hoa, null, commit = true) shouldBe SendInvitations.Result.Forbidden
        ResendInvitation(db)(outsider, hoa, "Unit 1", "owner1@example.test") shouldBe ResendInvitation.Result.Forbidden
        RevokeInvitation(db)(outsider, hoa, "Unit 1", "owner1@example.test", "r") shouldBe RevokeInvitation.Result.Forbidden
        ListInvitations(db)(outsider, hoa) shouldBe ListInvitations.Result.Forbidden
        db.dsl.fetchCount(OUTBOX_JOB) shouldBe 0
    }
}
