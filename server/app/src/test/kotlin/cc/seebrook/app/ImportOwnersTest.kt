package cc.seebrook.app

import cc.seebrook.app.devtools.DevSeed
import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.db.generated.identity.Tables.INVITATION
import cc.seebrook.db.generated.identity.Tables.OWNER_IMPORT
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.identity.AcceptInvitation
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.GrantRole
import cc.seebrook.identity.ImportOwners
import cc.seebrook.identity.ImportOwners.Result
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.identity.OwnerCsv
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.config.Environment
import cc.seebrook.platform.db.Database
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import cc.seebrook.db.generated.property.Tables.PROPERTY_ as PROPERTY

/** Owner CSV import (GA-05, GA-14, ED-34): atomic, idempotent, conservative about existing owners. */
class ImportOwnersTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val hoa: UUID = Fixtures.hoa(db, "hoa-a")
    private val import = ImportOwners(db)
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val header = OwnerCsv.COLUMNS.joinToString(",")

    @AfterEach
    fun close() = db.close()

    private fun csv(vararg rows: String) = (listOf(header) + rows).joinToString("\n").toByteArray()
    private fun row(unit: Int, email: String? = "owner$unit@example.test", street: String = "$unit Harbor Dr") =
        "Unit $unit,$street,,Ocean Isle Beach,NC,28469,${email.orEmpty()},"

    private fun commit(content: ByteArray, actor: UserActor = root) =
        import(actor, hoa, "owners.csv", content, commit = true, reason = "Initial roster from the board")

    private fun counts() = Triple(
        db.dsl.fetchCount(PROPERTY),
        db.dsl.fetchCount(INVITATION),
        db.dsl.fetchCount(OWNER_IMPORT),
    )

    @Test
    fun `dry run reports the plan and changes nothing`() {
        val result = import(root, hoa, "owners.csv", csv(row(1), row(2)), commit = false)

        val plan = result.shouldBeInstanceOf<Result.Planned>().plan
        plan.propertiesToCreate.map { it.unitLabel } shouldBe listOf("Unit 1", "Unit 2")
        plan.invitationsToCreate.map { it.email } shouldBe listOf("owner1@example.test", "owner2@example.test")
        counts() shouldBe Triple(0, 0, 0)
        db.dsl.fetchCount(EVENT) shouldBe 1 // only the Root bootstrap
    }

    @Test
    fun `commit creates properties and unsent invitations, audited`() {
        val result = commit(csv(row(1), row(2), row(3, email = null)))

        val imported = result.shouldBeInstanceOf<Result.Imported>()
        imported.plan.propertiesToCreate.size shouldBe 3
        counts() shouldBe Triple(3, 2, 1)
        db.dsl.selectFrom(INVITATION).fetch().forEach {
            it.status shouldBe "pending"
            it.tokenHash shouldBe null
            it.sentAt shouldBe null
            it.ownerImportId shouldBe imported.importId
        }
        val event = db.dsl.selectFrom(EVENT).where(EVENT.ACTION.eq("identity.owners_imported")).fetchSingle()
        event.actorUserId shouldBe root.userId
        event.reason shouldBe "Initial roster from the board"
        event.details.data() shouldContain "owner2@example.test"
    }

    @Test
    fun `re-running the same file changes nothing`() {
        val first = commit(csv(row(1), row(2))).shouldBeInstanceOf<Result.Imported>()
        val again = commit(csv(row(1), row(2)))

        again.shouldBeInstanceOf<Result.AlreadyImported>().importId shouldBe first.importId
        counts() shouldBe Triple(2, 2, 1)
    }

    @Test
    fun `a different file whose changes are already applied creates nothing`() {
        commit(csv(row(1), row(2)))
        val reordered = commit(csv(row(2), row(1), row(3)))

        val plan = reordered.shouldBeInstanceOf<Result.Imported>().plan
        plan.propertiesToCreate.map { it.unitLabel } shouldBe listOf("Unit 3")
        plan.invitationsToCreate.map { it.unitLabel } shouldBe listOf("Unit 3")
        plan.unchangedUnits shouldBe 2
        counts() shouldBe Triple(3, 3, 2)
    }

    @Test
    fun `an invalid row rejects the whole file`() {
        val result = commit(csv(row(1), row(2), "Unit 3,3 Harbor Dr,,Ocean Isle Beach,NC,bad-zip,owner3@example.test,"))

        result.shouldBeInstanceOf<Result.Invalid>().errors.single().line shouldBe 4
        counts() shouldBe Triple(0, 0, 0)
    }

    @Test
    fun `a different owner for an owned unit is a conflict, not a silent transfer`() {
        commit(csv(row(1), row(2)))
        // Unit 1's owner accepts, so they become an active owner.
        val token = IssueInvitation(db)(hoa, unitId("Unit 1"), "owner1@example.test").token
        AcceptInvitation(db)(TestAuth.identity("uid-1", "owner1@example.test"), token)
            .shouldBeInstanceOf<AcceptInvitation.Result.Accepted>()

        val result = commit(csv(row(1, email = "new-owner@example.test"), row(2, email = "other@example.test"), row(4)))

        val conflicts = result.shouldBeInstanceOf<Result.Conflicts>().conflicts
        conflicts.map { it.unitLabel } shouldBe listOf("Unit 1", "Unit 2")
        conflicts[0].message shouldContain "transfer"
        db.dsl.fetchCount(PROPERTY) shouldBe 2 // Unit 4 was not created either
    }

    @Test
    fun `existing unit details must match`() {
        commit(csv(row(1)))
        val result = commit(csv(row(1, street = "99 Elsewhere Rd")))

        result.shouldBeInstanceOf<Result.Conflicts>().conflicts.single().message shouldContain "address_line1"
    }

    @Test
    fun `seeded units without owners receive invitations`() {
        DevSeed.run(db, Environment.TEST)
        val harbor = requireNotNull(db.dsl.select(PROPERTY.HOA_ID).from(PROPERTY).limit(1).fetchOne(PROPERTY.HOA_ID))
        val content = requireNotNull(javaClass.getResourceAsStream("/dev-fixtures/channel-harbor/owners.csv")).readBytes()

        val plan = import(root, harbor, "owners.csv", content, commit = true, reason = "Synthetic roster")
            .shouldBeInstanceOf<Result.Imported>().plan

        plan.propertiesToCreate.shouldBeEmpty()
        plan.invitationsToCreate.size shouldBe 32
    }

    @Test
    fun `only operators who manage membership in the HOA may import`() {
        val otherHoa = Fixtures.hoa(db, "hoa-b")
        db.dsl.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, "docs@example.test").execute()
        db.dsl.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, "member@example.test").execute()
        GrantRole(db)(root, "docs@example.test", Role.DOCUMENT_ADMIN, hoa, "r")
        GrantRole(db)(root, "member@example.test", Role.MEMBERSHIP_ADMIN, otherHoa, "r")
        fun actor(email: String) = (ResolveOperator(db)(email) as ResolveOperator.Result.Active).actor

        commit(csv(row(1)), actor("docs@example.test")) shouldBe Result.Forbidden
        commit(csv(row(1)), actor("member@example.test")) shouldBe Result.Forbidden
        import(root, hoa, "owners.csv", csv(row(1)), commit = true, reason = " ") shouldBe Result.ReasonRequired
        counts() shouldBe Triple(0, 0, 0)
    }

    private fun unitId(label: String): UUID =
        requireNotNull(db.dsl.select(PROPERTY.ID).from(PROPERTY).where(PROPERTY.UNIT_LABEL.eq(label)).fetchOne(PROPERTY.ID))
}
