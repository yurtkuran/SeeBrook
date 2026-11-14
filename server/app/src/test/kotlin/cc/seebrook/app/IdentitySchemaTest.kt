package cc.seebrook.app

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.identity.Tables.INVITATION
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.db.generated.property.Tables.OWNERSHIP
import cc.seebrook.platform.db.Database
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/** Database-level guarantees for users, ownership and invitations (GA-05, GA-06, ED-26). */
class IdentitySchemaTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val hoaA = Fixtures.hoa(db, "hoa-a")
    private val hoaB = Fixtures.hoa(db, "hoa-b")
    private val unitA = Fixtures.property(db, hoaA, "Unit 01")
    private val unitB = Fixtures.property(db, hoaB, "Unit 01")

    @AfterEach
    fun close() = db.close()

    private fun user(email: String): UUID = requireNotNull(
        db.dsl.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, email).returning(USER_ACCOUNT.ID).fetchSingle().id,
    )

    private fun ownership(hoaId: UUID, propertyId: UUID, userId: UUID) =
        db.dsl.insertInto(OWNERSHIP)
            .set(OWNERSHIP.HOA_ID, hoaId).set(OWNERSHIP.PROPERTY_ID, propertyId).set(OWNERSHIP.USER_ID, userId)
            .execute()

    private fun invitation(hoaId: UUID, propertyId: UUID, email: String, hash: String) =
        db.dsl.insertInto(INVITATION)
            .set(INVITATION.HOA_ID, hoaId).set(INVITATION.PROPERTY_ID, propertyId)
            .set(INVITATION.EMAIL, email).set(INVITATION.TOKEN_HASH, hash)
            .set(INVITATION.EXPIRES_AT, OffsetDateTime.now().plusDays(1))
            .execute()

    @Test
    fun `ownership cannot link an HOA to another HOA's property`() {
        val owner = user("owner@example.test")
        shouldThrow<DataAccessException> { ownership(hoaA, unitB.propertyId, owner) }
            .message shouldContain "ownership_property_fk"
    }

    @Test
    fun `a user has at most one active ownership per property`() {
        val owner = user("owner@example.test")
        ownership(hoaA, unitA.propertyId, owner)
        shouldThrow<DataAccessException> { ownership(hoaA, unitA.propertyId, owner) }
            .message shouldContain "ownership_active_unique"

        // After it ends, a new ownership period may start (e.g. the unit is bought back).
        db.dsl.update(OWNERSHIP).set(OWNERSHIP.ENDED_AT, DSL.currentOffsetDateTime()).execute()
        ownership(hoaA, unitA.propertyId, owner)
    }

    @Test
    fun `invitation cannot link an HOA to another HOA's property`() {
        shouldThrow<DataAccessException> { invitation(hoaA, unitB.propertyId, "x@example.test", "a".repeat(64)) }
            .message shouldContain "invitation_property_fk"
    }

    @Test
    fun `one pending invitation per property and email`() {
        invitation(hoaA, unitA.propertyId, "x@example.test", "a".repeat(64))
        shouldThrow<DataAccessException> { invitation(hoaA, unitA.propertyId, "x@example.test", "b".repeat(64)) }
            .message shouldContain "invitation_pending_unique"
    }

    @Test
    fun `accepted invitation must record who accepted it`() {
        invitation(hoaA, unitA.propertyId, "x@example.test", "a".repeat(64))
        shouldThrow<DataAccessException> {
            db.dsl.update(INVITATION).set(INVITATION.STATUS, "accepted").execute()
        }.message shouldContain "invitation_accepted_consistent"
    }

    @Test
    fun `user email is unique`() {
        user("owner@example.test")
        shouldThrow<DataAccessException> { user("owner@example.test") }
            .message shouldContain "user_account_email_unique"
    }
}
