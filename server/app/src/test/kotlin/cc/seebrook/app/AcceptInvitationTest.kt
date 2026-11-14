package cc.seebrook.app

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.identity.Tables.EXTERNAL_IDENTITY
import cc.seebrook.db.generated.identity.Tables.INVITATION
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.db.generated.property.Tables.OWNERSHIP
import cc.seebrook.identity.AcceptInvitation
import cc.seebrook.identity.AcceptInvitation.Result
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.platform.db.Database
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Invitation rules (GA-05, ED-29): single-use, expiring, bound to a verified email and one property. */
class AcceptInvitationTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val hoa = Fixtures.hoa(db, "hoa-a")
    private val unit = Fixtures.property(db, hoa, "Unit 01")
    private val issue = IssueInvitation(db)
    private val accept = AcceptInvitation(db)

    @AfterEach
    fun close() = db.close()

    private fun invite(email: String = "owner@example.test") = issue(unit.hoaId, unit.propertyId, email).token
    private fun ownerships() = db.dsl.fetchCount(OWNERSHIP)
    private fun users() = db.dsl.fetchCount(USER_ACCOUNT)

    @Test
    fun `acceptance creates the user, links the sign-in and grants ownership atomically`() {
        val result = accept(TestAuth.identity("uid-1", "Owner@Example.TEST"), invite())

        val accepted = result.shouldBeInstanceOf<Result.Accepted>()
        accepted.propertyId shouldBe unit.propertyId
        db.dsl.fetchSingle(USER_ACCOUNT).email shouldBe "owner@example.test"
        db.dsl.fetchSingle(EXTERNAL_IDENTITY).subject shouldBe "uid-1"
        db.dsl.fetchSingle(OWNERSHIP).userId shouldBe accepted.userId
        db.dsl.fetchSingle(INVITATION).let {
            it.status shouldBe "accepted"
            it.acceptedBy shouldBe accepted.userId
        }
    }

    @Test
    fun `invitation can be used only once`() {
        val token = invite()
        accept(TestAuth.identity("uid-1", "owner@example.test"), token).shouldBeInstanceOf<Result.Accepted>()

        // Another account with the same verified email (e.g. a second provider) cannot reuse it.
        accept(TestAuth.identity("uid-2", "owner@example.test"), token) shouldBe Result.NoLongerValid
        ownerships() shouldBe 1
    }

    @Test
    fun `retry by the accepting account is idempotent`() {
        val token = invite()
        val first = accept(TestAuth.identity("uid-1", "owner@example.test"), token)
        val second = accept(TestAuth.identity("uid-1", "owner@example.test"), token)

        second shouldBe first
        ownerships() shouldBe 1
        users() shouldBe 1
    }

    @Test
    fun `email must match the invitation`() {
        accept(TestAuth.identity("uid-1", "someone-else@example.test"), invite()) shouldBe Result.EmailMismatch
        users() shouldBe 0
        ownerships() shouldBe 0
    }

    @Test
    fun `email must be verified`() {
        val token = invite()
        accept(TestAuth.identity("uid-1", "owner@example.test", emailVerified = false), token) shouldBe
            Result.EmailNotVerified
        accept(TestAuth.identity("uid-1", null), token) shouldBe Result.EmailNotVerified
        ownerships() shouldBe 0
    }

    @Test
    fun `expired invitation is refused`() {
        val token = invite()
        val later = AcceptInvitation(db, Clock.fixed(Instant.now().plus(Duration.ofDays(15)), ZoneOffset.UTC))

        later(TestAuth.identity("uid-1", "owner@example.test"), token) shouldBe Result.Expired
        ownerships() shouldBe 0
    }

    @Test
    fun `reissuing revokes the previous invitation`() {
        val old = invite()
        val new = invite()

        accept(TestAuth.identity("uid-1", "owner@example.test"), old) shouldBe Result.NoLongerValid
        accept(TestAuth.identity("uid-1", "owner@example.test"), new).shouldBeInstanceOf<Result.Accepted>()
    }

    @Test
    fun `unknown or malformed tokens are not found`() {
        invite()
        val identity = TestAuth.identity("uid-1", "owner@example.test")
        accept(identity, "A".repeat(43)) shouldBe Result.NotFound
        accept(identity, "short") shouldBe Result.NotFound
        accept(identity, "") shouldBe Result.NotFound
    }

    @Test
    fun `disabled user cannot accept`() {
        accept(TestAuth.identity("uid-1", "owner@example.test"), invite()).shouldBeInstanceOf<Result.Accepted>()
        db.dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.STATUS, "disabled").execute()
        val second = Fixtures.property(db, hoa, "Unit 02")
        val token = issue(second.hoaId, second.propertyId, "owner@example.test").token

        accept(TestAuth.identity("uid-1", "owner@example.test"), token) shouldBe Result.AccountDisabled
        ownerships() shouldBe 1
    }

    @Test
    fun `existing user signing in with another provider is linked by verified email`() {
        val first = accept(TestAuth.identity("uid-password", "owner@example.test"), invite())
            .shouldBeInstanceOf<Result.Accepted>()
        val second = Fixtures.property(db, hoa, "Unit 02")
        val token = issue(second.hoaId, second.propertyId, "owner@example.test").token

        val result = accept(TestAuth.identity("uid-google", "owner@example.test"), token)
            .shouldBeInstanceOf<Result.Accepted>()
        result.userId shouldBe first.userId
        users() shouldBe 1
        db.dsl.fetchCount(EXTERNAL_IDENTITY) shouldBe 2
    }

    @Test
    fun `raw tokens are never stored`() {
        val token = invite()
        db.dsl.fetchSingle(INVITATION).tokenHash.let {
            it.length shouldBe 64
            (it == token) shouldBe false
        }
    }
}
