package cc.seebrook.app

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.db.generated.identity.Tables.ROLE_ASSIGNMENT
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.GrantRole
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.identity.RevokeRole
import cc.seebrook.platform.auth.Channel
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Role rules (GA-03, GA-04 controlled grants, ED-31, ED-32), each change audited (ED-33). */
class RolesTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val hoaA = Fixtures.hoa(db, "hoa-a")
    private val hoaB = Fixtures.hoa(db, "hoa-b")
    private val grant = GrantRole(db)
    private val revoke = RevokeRole(db)
    private val reason = "Board resolution 2026-11"

    @AfterEach
    fun close() = db.close()

    private fun user(email: String) {
        db.dsl.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, email).execute()
    }

    private fun operator(email: String): UserActor =
        ResolveOperator(db)(email).shouldBeInstanceOf<ResolveOperator.Result.Active>().actor

    private fun root(): UserActor {
        BootstrapRoot(db)("root@example.test").shouldBeInstanceOf<BootstrapRoot.Result.Created>()
        return operator("root@example.test")
    }

    private fun auditActions() = db.dsl.select(EVENT.ACTION).from(EVENT).orderBy(EVENT.ID).fetch(EVENT.ACTION)

    @Test
    fun `bootstrap creates the first Root once, audited`() {
        val actor = root()
        actor.isRoot shouldBe true
        actor.channel shouldBe Channel.CLI
        actor.can(Permission.MANAGE_FINANCES, hoaA) shouldBe true

        BootstrapRoot(db)("someone@example.test") shouldBe BootstrapRoot.Result.RootAlreadyExists
        db.dsl.fetchCount(ROLE_ASSIGNMENT) shouldBe 1
        auditActions() shouldBe listOf("identity.root_bootstrapped")
    }

    @Test
    fun `Root grants an HOA Admin, who can then manage roles only in that HOA`() {
        val root = root()
        user("admin@example.test")
        user("member@example.test")
        grant(root, "admin@example.test", Role.HOA_ADMIN, hoaA, reason).shouldBeInstanceOf<GrantRole.Result.Granted>()

        val admin = operator("admin@example.test")
        admin.can(Permission.MANAGE_ROLES, hoaA) shouldBe true
        admin.can(Permission.MANAGE_ROLES, hoaB) shouldBe false
        admin.can(Permission.MANAGE_DOCUMENTS, hoaA) shouldBe false

        grant(admin, "member@example.test", Role.MEMBERSHIP_ADMIN, hoaA, reason)
            .shouldBeInstanceOf<GrantRole.Result.Granted>()
        grant(admin, "member@example.test", Role.MEMBERSHIP_ADMIN, hoaB, reason) shouldBe GrantRole.Result.Forbidden
    }

    @Test
    fun `only Root grants Root`() {
        val root = root()
        user("admin@example.test")
        grant(root, "admin@example.test", Role.HOA_ADMIN, hoaA, reason)
        val admin = operator("admin@example.test")

        grant(admin, "admin@example.test", Role.ROOT, null, reason) shouldBe GrantRole.Result.Forbidden
        grant(root, "admin@example.test", Role.ROOT, null, reason).shouldBeInstanceOf<GrantRole.Result.Granted>()
    }

    @Test
    fun `roles without MANAGE_ROLES cannot grant`() {
        val root = root()
        user("member@example.test")
        user("other@example.test")
        grant(root, "member@example.test", Role.MEMBERSHIP_ADMIN, hoaA, reason)

        grant(operator("member@example.test"), "other@example.test", Role.DOCUMENT_ADMIN, hoaA, reason) shouldBe
            GrantRole.Result.Forbidden
    }

    @Test
    fun `grants need a reason, a valid scope and an existing user`() {
        val root = root()
        user("admin@example.test")

        grant(root, "admin@example.test", Role.HOA_ADMIN, hoaA, "  ") shouldBe GrantRole.Result.ReasonRequired
        grant(root, "admin@example.test", Role.HOA_ADMIN, hoaA, "x".repeat(1001)) shouldBe GrantRole.Result.ReasonRequired
        grant(root, "admin@example.test", Role.HOA_ADMIN, null, reason) shouldBe GrantRole.Result.InvalidScope
        grant(root, "admin@example.test", Role.ROOT, hoaA, reason) shouldBe GrantRole.Result.InvalidScope
        grant(root, "nobody@example.test", Role.HOA_ADMIN, hoaA, reason) shouldBe GrantRole.Result.UserNotFound
    }

    @Test
    fun `granting an active role again changes nothing`() {
        val root = root()
        user("admin@example.test")
        grant(root, "Admin@Example.test", Role.HOA_ADMIN, hoaA, reason).shouldBeInstanceOf<GrantRole.Result.Granted>()
        grant(root, "admin@example.test", Role.HOA_ADMIN, hoaA, reason) shouldBe GrantRole.Result.AlreadyGranted
        db.dsl.fetchCount(ROLE_ASSIGNMENT) shouldBe 2 // Root + one HOA Admin
    }

    @Test
    fun `HOA Admin cannot remove the last HOA Admin, Root can`() {
        val root = root()
        user("admin@example.test")
        grant(root, "admin@example.test", Role.HOA_ADMIN, hoaA, reason)
        val admin = operator("admin@example.test")

        revoke(admin, "admin@example.test", Role.HOA_ADMIN, hoaA, reason) shouldBe RevokeRole.Result.LastHoaAdmin
        revoke(root, "admin@example.test", Role.HOA_ADMIN, hoaA, reason) shouldBe RevokeRole.Result.Revoked
        operator("admin@example.test").can(Permission.MANAGE_ROLES, hoaA) shouldBe false
    }

    @Test
    fun `HOA Admin can remove another HOA Admin while one remains`() {
        val root = root()
        user("a1@example.test")
        user("a2@example.test")
        grant(root, "a1@example.test", Role.HOA_ADMIN, hoaA, reason)
        grant(root, "a2@example.test", Role.HOA_ADMIN, hoaA, reason)

        revoke(operator("a1@example.test"), "a2@example.test", Role.HOA_ADMIN, hoaA, reason) shouldBe
            RevokeRole.Result.Revoked
    }

    @Test
    fun `the last Root cannot be revoked`() {
        val root = root()
        revoke(root, "root@example.test", Role.ROOT, null, reason) shouldBe RevokeRole.Result.LastRoot

        user("second@example.test")
        grant(root, "second@example.test", Role.ROOT, null, reason)
        revoke(root, "root@example.test", Role.ROOT, null, reason) shouldBe RevokeRole.Result.Revoked
        operator("root@example.test").isRoot shouldBe false
    }

    @Test
    fun `revoking a role that is not held is not found`() {
        val root = root()
        user("admin@example.test")
        revoke(root, "admin@example.test", Role.HOA_ADMIN, hoaA, reason) shouldBe RevokeRole.Result.NotFound
    }

    @Test
    fun `role changes are audited with actor, channel, reason and values`() {
        val root = root()
        user("admin@example.test")
        grant(root, "admin@example.test", Role.HOA_ADMIN, hoaA, reason)
        revoke(root, "admin@example.test", Role.HOA_ADMIN, hoaA, "Left the board")

        auditActions() shouldBe listOf("identity.root_bootstrapped", "identity.role_granted", "identity.role_revoked")
        val revoked = db.dsl.selectFrom(EVENT).where(EVENT.ACTION.eq("identity.role_revoked")).fetchSingle()
        revoked.actorUserId shouldBe root.userId
        revoked.channel shouldBe "cli"
        revoked.hoaId shouldBe hoaA
        revoked.reason shouldBe "Left the board"
        val details = Json.parseToJsonElement(revoked.details.data()).jsonObject
        details["role"]?.jsonPrimitive?.content shouldBe "hoa_admin"
        details["userEmail"]?.jsonPrimitive?.content shouldBe "admin@example.test"
    }
}
