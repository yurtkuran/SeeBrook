package cc.seebrook.app.http

import cc.seebrook.app.devtools.DevSeed
import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.db.generated.property.Tables.OWNERSHIP
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.platform.config.Environment
import cc.seebrook.platform.db.Database
import cc.seebrook.shared.api.AcceptInvitationRequest
import cc.seebrook.shared.api.AcceptInvitationResponse
import cc.seebrook.shared.api.ErrorResponse
import cc.seebrook.shared.api.MeResponse
import cc.seebrook.shared.api.PropertiesResponse
import cc.seebrook.shared.api.PropertyResponse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import cc.seebrook.db.generated.property.Tables.PROPERTY_ as PROPERTY

/**
 * M1 exit criterion, end to end over HTTP: an owner who accepts an invitation sees only their
 * assigned unit — never another unit, never another HOA's property, and nothing once ownership ends.
 */
class OwnerAccessTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val issue = IssueInvitation(db)

    @AfterEach
    fun close() = db.close()

    private fun unit(label: String): Fixtures.Unit {
        val row = db.dsl.select(PROPERTY.HOA_ID, PROPERTY.ID).from(PROPERTY)
            .where(PROPERTY.UNIT_LABEL.eq(label)).fetchSingle()
        return Fixtures.Unit(requireNotNull(row.value1()), requireNotNull(row.value2()))
    }

    private fun invite(unit: Fixtures.Unit, email: String) = issue(unit.hoaId, unit.propertyId, email).token

    private fun ApplicationTestBuilder.client(): HttpClient {
        application { seebrookModule(db, TestAuth.verifier) }
        return createClient { install(ContentNegotiation) { json() } }
    }

    private suspend fun HttpClient.accept(token: String, invitation: String): HttpResponse =
        post("/v1/invitations/accept") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(AcceptInvitationRequest(invitation))
        }

    private suspend fun HttpClient.myProperties(token: String): List<PropertyResponse> =
        get("/v1/me/properties") { bearerAuth(token) }.also { it.status shouldBe HttpStatusCode.OK }
            .body<PropertiesResponse>().properties

    @Test
    fun `owner who accepts an invitation sees only their assigned unit`() = testApplication {
        DevSeed.run(db, Environment.TEST)
        val unit01 = unit("Unit 01")
        val unit02 = unit("Unit 02")
        val owner1 = TestAuth.token("uid-owner-1", "owner1@example.test")
        val owner2 = TestAuth.token("uid-owner-2", "owner2@example.test")
        val http = client()

        val accepted = http.accept(owner1, invite(unit01, "Owner1@Example.test"))
        accepted.status shouldBe HttpStatusCode.OK
        accepted.body<AcceptInvitationResponse>().propertyId shouldBe unit01.propertyId.toString()
        http.accept(owner2, invite(unit02, "owner2@example.test")).status shouldBe HttpStatusCode.OK

        val mine = http.myProperties(owner1)
        mine.map { it.unitLabel } shouldBe listOf("Unit 01")
        mine.single().id shouldBe unit01.propertyId.toString()

        http.get("/v1/properties/${unit01.propertyId}") { bearerAuth(owner1) }.status shouldBe HttpStatusCode.OK
        // Another owner's unit in the same HOA looks exactly like a missing one.
        val other = http.get("/v1/properties/${unit02.propertyId}") { bearerAuth(owner1) }
        other.status shouldBe HttpStatusCode.NotFound
        other.body<ErrorResponse>().error shouldBe "not_found"
        http.get("/v1/properties/${UUID.randomUUID()}") { bearerAuth(owner1) }.status shouldBe HttpStatusCode.NotFound
        http.get("/v1/properties/not-a-uuid") { bearerAuth(owner1) }.status shouldBe HttpStatusCode.NotFound

        val me = http.get("/v1/me") { bearerAuth(owner1) }.body<MeResponse>()
        me.email shouldBe "owner1@example.test"
    }

    @Test
    fun `owner cannot see another HOA's property`() = testApplication {
        val hoaA = Fixtures.hoa(db, "hoa-a")
        val hoaB = Fixtures.hoa(db, "hoa-b")
        val unitA = Fixtures.property(db, hoaA, "Unit 01")
        val unitB = Fixtures.property(db, hoaB, "Unit 01")
        val ownerA = TestAuth.token("uid-a", "a@example.test")
        val ownerB = TestAuth.token("uid-b", "b@example.test")
        val http = client()
        http.accept(ownerA, invite(unitA, "a@example.test")).status shouldBe HttpStatusCode.OK
        http.accept(ownerB, invite(unitB, "b@example.test")).status shouldBe HttpStatusCode.OK

        http.myProperties(ownerA).map { it.id } shouldBe listOf(unitA.propertyId.toString())
        http.myProperties(ownerB).map { it.id } shouldBe listOf(unitB.propertyId.toString())
        http.get("/v1/properties/${unitB.propertyId}") { bearerAuth(ownerA) }.status shouldBe HttpStatusCode.NotFound
        http.get("/v1/properties/${unitA.propertyId}") { bearerAuth(ownerB) }.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `owner with several properties keeps access to the others when one ownership ends`() = testApplication {
        val hoaA = Fixtures.hoa(db, "hoa-a")
        val hoaB = Fixtures.hoa(db, "hoa-b")
        val unitA1 = Fixtures.property(db, hoaA, "Unit 01")
        val unitA2 = Fixtures.property(db, hoaA, "Unit 02")
        val unitB1 = Fixtures.property(db, hoaB, "Unit 01")
        val owner = TestAuth.token("uid-multi", "multi@example.test")
        val http = client()
        for (unit in listOf(unitA1, unitA2, unitB1)) {
            http.accept(owner, invite(unit, "multi@example.test")).status shouldBe HttpStatusCode.OK
        }
        http.myProperties(owner).map { it.id }.toSet() shouldBe
            setOf(unitA1, unitA2, unitB1).map { it.propertyId.toString() }.toSet()

        // Simulates the outcome of an ownership transfer (the TransferOwnership action arrives in M2).
        db.dsl.update(OWNERSHIP).set(OWNERSHIP.ENDED_AT, DSL.currentOffsetDateTime())
            .where(OWNERSHIP.PROPERTY_ID.eq(unitA1.propertyId)).execute()

        // Revocation takes effect on the very next request, with the same still-valid token (GA-07).
        http.get("/v1/properties/${unitA1.propertyId}") { bearerAuth(owner) }.status shouldBe HttpStatusCode.NotFound
        http.myProperties(owner).map { it.id }.toSet() shouldBe
            setOf(unitA2, unitB1).map { it.propertyId.toString() }.toSet()
    }

    @Test
    fun `owner whose only ownership ends sees nothing`() = testApplication {
        val hoa = Fixtures.hoa(db, "hoa-a")
        val unit = Fixtures.property(db, hoa, "Unit 01")
        val owner = TestAuth.token("uid-1", "one@example.test")
        val http = client()
        http.accept(owner, invite(unit, "one@example.test")).status shouldBe HttpStatusCode.OK

        db.dsl.update(OWNERSHIP).set(OWNERSHIP.ENDED_AT, DSL.currentOffsetDateTime()).execute()

        http.myProperties(owner).shouldBeEmpty()
        http.get("/v1/properties/${unit.propertyId}") { bearerAuth(owner) }.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `disabled user is refused immediately`() = testApplication {
        val hoa = Fixtures.hoa(db, "hoa-a")
        val unit = Fixtures.property(db, hoa, "Unit 01")
        val owner = TestAuth.token("uid-1", "one@example.test")
        val http = client()
        http.accept(owner, invite(unit, "one@example.test")).status shouldBe HttpStatusCode.OK

        db.dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.STATUS, "disabled").execute()

        for (path in listOf("/v1/me", "/v1/me/properties", "/v1/properties/${unit.propertyId}")) {
            val response = http.get(path) { bearerAuth(owner) }
            response.status shouldBe HttpStatusCode.Forbidden
            response.body<ErrorResponse>().error shouldBe "account_disabled"
        }
    }

    @Test
    fun `signed-in user without an accepted invitation is not registered`() = testApplication {
        val stranger = TestAuth.token("uid-stranger", "stranger@example.test")
        val http = client()

        for (path in listOf("/v1/me", "/v1/me/properties", "/v1/properties/${UUID.randomUUID()}")) {
            val response = http.get(path) { bearerAuth(stranger) }
            response.status shouldBe HttpStatusCode.Forbidden
            response.body<ErrorResponse>().error shouldBe "not_registered"
        }
    }

    @Test
    fun `requests without a valid token are rejected`() = testApplication {
        val http = client()

        http.get("/v1/me/properties").status shouldBe HttpStatusCode.Unauthorized
        http.get("/v1/me/properties") { bearerAuth("not-a-token") }.status shouldBe HttpStatusCode.Unauthorized
        http.get("/v1/me/properties") { header("Authorization", "Basic dXNlcjpwYXNz") }.status shouldBe
            HttpStatusCode.Unauthorized
        http.post("/v1/invitations/accept").status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `malformed acceptance request is a bad request`() = testApplication {
        val http = client()
        val response = http.post("/v1/invitations/accept") {
            bearerAuth(TestAuth.token("uid-1", "one@example.test"))
            contentType(ContentType.Application.Json)
            setBody("""{"nope": 1}""")
        }
        response.status shouldBe HttpStatusCode.BadRequest
    }
}
