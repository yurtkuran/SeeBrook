package cc.seebrook.app

import cc.seebrook.app.http.seebrookModule
import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.db.generated.identity.Tables.INVITATION
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.db.generated.jobs.Tables.OUTBOX_JOB
import cc.seebrook.db.generated.property.Tables.OWNERSHIP
import cc.seebrook.identity.AcceptInvitation
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.GrantRole
import cc.seebrook.identity.ImportOwners
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.identity.SendInvitations
import cc.seebrook.identity.TransferOwnership
import cc.seebrook.identity.TransferOwnership.Result
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import cc.seebrook.db.generated.property.Tables.PROPERTY_ as PROPERTY

/** Ownership transfer (GA-07, GA-09, ED-37): atomic, audited, effective on the next request. */
class TransferOwnershipTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val hoa: UUID = Fixtures.hoa(db, "hoa-a")
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val transfer = TransferOwnership(db)

    init {
        val csv = """
            unit_label,address_line1,address_line2,city,state_code,postal_code,owner_email,owner_name
            Unit 1,1 Harbor Dr,,Ocean Isle Beach,NC,28469,owner1@example.test,
            Unit 2,2 Harbor Dr,,Ocean Isle Beach,NC,28469,owner2@example.test,
        """.trimIndent().toByteArray()
        ImportOwners(db)(root, hoa, "owners.csv", csv, commit = true, reason = "Roster")
            .shouldBeInstanceOf<ImportOwners.Result.Imported>()
        // owner1 accepts and becomes Unit 1's active owner; owner2 stays invited (unsent).
        val token = IssueInvitation(db)(hoa, unitId("Unit 1"), "owner1@example.test").token
        AcceptInvitation(db)(TestAuth.identity("uid-1", "owner1@example.test"), token)
            .shouldBeInstanceOf<AcceptInvitation.Result.Accepted>()
    }

    @AfterEach
    fun close() = db.close()

    private fun unitId(label: String): UUID =
        requireNotNull(db.dsl.select(PROPERTY.ID).from(PROPERTY).where(PROPERTY.UNIT_LABEL.eq(label)).fetchOne(PROPERTY.ID))

    private fun commit(unit: String, vararg to: String, actor: UserActor = root) =
        transfer(actor, hoa, unit, to.toList(), commit = true, reason = "Sale recorded 2026-11-15")

    private fun activeOwnerCount(unit: String) =
        db.dsl.fetchCount(OWNERSHIP, OWNERSHIP.PROPERTY_ID.eq(unitId(unit)).and(OWNERSHIP.ENDED_AT.isNull))

    private fun pendingEmails(unit: String) =
        db.dsl.select(INVITATION.EMAIL).from(INVITATION)
            .where(INVITATION.PROPERTY_ID.eq(unitId(unit))).and(INVITATION.STATUS.eq("pending"))
            .fetch(INVITATION.EMAIL).sorted()

    @Test
    fun `dry run shows the change and changes nothing`() {
        val plan = transfer(root, hoa, "Unit 1", listOf("Buyer@Example.test"), commit = false)
            .shouldBeInstanceOf<Result.Planned>().plan

        plan.ending shouldBe listOf("owner1@example.test")
        plan.inviting shouldBe listOf("buyer@example.test")
        activeOwnerCount("Unit 1") shouldBe 1
        pendingEmails("Unit 1") shouldBe emptyList()
    }

    @Test
    fun `a sale ends the seller's access at once and invites the buyer, audited`() = testApplication {
        application { seebrookModule(db, TestAuth.verifier) }
        val seller = TestAuth.token("uid-1", "owner1@example.test")
        val unit1 = unitId("Unit 1")
        client.get("/v1/properties/$unit1") { bearerAuth(seller) }.status shouldBe HttpStatusCode.OK

        commit("Unit 1", "buyer@example.test").shouldBeInstanceOf<Result.Transferred>()

        // The seller's still-valid sign-in token no longer reaches the unit (GA-07).
        client.get("/v1/properties/$unit1") { bearerAuth(seller) }.status shouldBe HttpStatusCode.NotFound
        activeOwnerCount("Unit 1") shouldBe 0
        db.dsl.fetchCount(OWNERSHIP, OWNERSHIP.PROPERTY_ID.eq(unit1)) shouldBe 1 // history kept, not deleted
        pendingEmails("Unit 1") shouldBe listOf("buyer@example.test")
        db.dsl.select(INVITATION.SENT_AT).from(INVITATION).where(INVITATION.EMAIL.eq("buyer@example.test"))
            .fetchSingle(INVITATION.SENT_AT) shouldBe null

        val event = db.dsl.selectFrom(EVENT).where(EVENT.ACTION.eq("identity.ownership_transferred")).fetchSingle()
        event.reason shouldBe "Sale recorded 2026-11-15"
        event.targetId shouldBe unit1
        event.details.data() shouldContain "owner1@example.test"
        event.details.data() shouldContain "buyer@example.test"
    }

    @Test
    fun `adding a co-owner keeps the existing owner's ownership untouched`() {
        val before = db.dsl.select(OWNERSHIP.ID).from(OWNERSHIP).where(OWNERSHIP.PROPERTY_ID.eq(unitId("Unit 1"))).fetchSingle(OWNERSHIP.ID)

        val plan = commit("Unit 1", "owner1@example.test", "spouse@example.test").shouldBeInstanceOf<Result.Transferred>().plan

        plan.ending shouldBe emptyList()
        plan.continuing shouldBe listOf("owner1@example.test")
        plan.inviting shouldBe listOf("spouse@example.test")
        db.dsl.select(OWNERSHIP.ID).from(OWNERSHIP)
            .where(OWNERSHIP.PROPERTY_ID.eq(unitId("Unit 1"))).and(OWNERSHIP.ENDED_AT.isNull)
            .fetchSingle(OWNERSHIP.ID) shouldBe before
    }

    @Test
    fun `an invited person who is no longer an owner loses the invitation and its queued email`() {
        SendInvitations(db)(root, hoa, "Unit 2", commit = true) // owner2's email is queued

        val plan = commit("Unit 2", "buyer2@example.test").shouldBeInstanceOf<Result.Transferred>().plan

        plan.revoking shouldBe listOf("owner2@example.test")
        pendingEmails("Unit 2") shouldBe listOf("buyer2@example.test")
        db.dsl.select(OUTBOX_JOB.STATUS).from(OUTBOX_JOB).fetch(OUTBOX_JOB.STATUS) shouldBe listOf("cancelled")
        db.dsl.select(INVITATION.STATUS).from(INVITATION).where(INVITATION.EMAIL.eq("owner2@example.test"))
            .fetchSingle(INVITATION.STATUS) shouldBe "revoked"
    }

    @Test
    fun `the seller can own the unit again later through a new invitation`() {
        commit("Unit 1", "buyer@example.test")
        commit("Unit 1", "owner1@example.test") // bought back

        val token = IssueInvitation(db)(hoa, unitId("Unit 1"), "owner1@example.test").token
        AcceptInvitation(db)(TestAuth.identity("uid-1", "owner1@example.test"), token)
            .shouldBeInstanceOf<AcceptInvitation.Result.Accepted>()
        activeOwnerCount("Unit 1") shouldBe 1
        db.dsl.fetchCount(OWNERSHIP, OWNERSHIP.PROPERTY_ID.eq(unitId("Unit 1"))) shouldBe 2
    }

    @Test
    fun `an owner import cannot undo a transfer`() {
        commit("Unit 1", "buyer@example.test")
        val original = """
            unit_label,address_line1,address_line2,city,state_code,postal_code,owner_email,owner_name
            Unit 1,1 Harbor Dr,,Ocean Isle Beach,NC,28469,owner1@example.test,
        """.trimIndent().toByteArray()

        ImportOwners(db)(root, hoa, "old.csv", original, commit = true, reason = "Re-import")
            .shouldBeInstanceOf<ImportOwners.Result.Conflicts>()
    }

    @Test
    fun `invalid requests change nothing`() {
        transfer(root, hoa, "Unit 1", listOf("owner1@example.test"), commit = true, reason = "r") shouldBe Result.NoChange
        transfer(root, hoa, "Unit 1", listOf(" "), commit = true, reason = "r") shouldBe Result.NoNewOwners
        transfer(root, hoa, "Unit 1", listOf("not-an-email"), commit = true, reason = "r") shouldBe Result.InvalidEmail("not-an-email")
        transfer(root, hoa, "Unit 9", listOf("x@example.test"), commit = true, reason = "r") shouldBe Result.UnitNotFound
        transfer(root, hoa, "Unit 1", listOf("x@example.test"), commit = true, reason = " ") shouldBe Result.ReasonRequired

        val otherHoa = Fixtures.hoa(db, "hoa-b")
        db.dsl.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, "member@example.test").execute()
        GrantRole(db)(root, "member@example.test", Role.MEMBERSHIP_ADMIN, otherHoa, "r")
        val outsider = (ResolveOperator(db)("member@example.test") as ResolveOperator.Result.Active).actor
        commit("Unit 1", "x@example.test", actor = outsider) shouldBe Result.Forbidden

        activeOwnerCount("Unit 1") shouldBe 1
        db.dsl.fetchCount(EVENT, EVENT.ACTION.eq("identity.ownership_transferred")) shouldBe 0
        pendingEmails("Unit 1") shouldNotBe listOf("x@example.test")
    }
}
