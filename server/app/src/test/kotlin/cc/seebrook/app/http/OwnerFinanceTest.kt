package cc.seebrook.app.http

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.db.generated.property.Tables.OWNERSHIP
import cc.seebrook.finance.ImportAccounts
import cc.seebrook.finance.ImportTransactions
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.property.Ownerships
import cc.seebrook.shared.api.AcceptInvitationRequest
import cc.seebrook.shared.api.AccountSummaryResponse
import cc.seebrook.shared.api.AccountTransactionsPageResponse
import cc.seebrook.shared.api.CarriedForwardResponse
import cc.seebrook.shared.api.ErrorResponse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * ED-42 over HTTP: an owner sees the selected property's balance, due and overdue amounts and the
 * transactions of their ownership period; earlier owners' transactions are one carried-forward
 * amount, and nobody else sees anything.
 */
class OwnerFinanceTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val hoaA = Fixtures.hoa(db, "hoa-a") // America/New_York
    private val hoaB = Fixtures.hoa(db, "hoa-b")
    private val unit1 = Fixtures.property(db, hoaA, "Unit 01")
    private val unit2 = Fixtures.property(db, hoaA, "Unit 02")
    private val unmapped = Fixtures.property(db, hoaA, "Unit 03")
    private val unitB = Fixtures.property(db, hoaB, "Unit 01")

    /** 10 July 2026, 11:00 in New York. */
    private val clock = Clock.fixed(Instant.parse("2026-07-10T15:00:00Z"), ZoneOffset.UTC)

    init {
        ImportAccounts(db)(root, hoaA, "unit_label,account\nUnit 01,A-01\nUnit 02,A-02\n".toByteArray(), commit = true, reason = "map")
            .shouldBeInstanceOf<ImportAccounts.Result.Imported>()
        ImportAccounts(db)(root, hoaB, "unit_label,account\nUnit 01,A-01\n".toByteArray(), commit = true, reason = "map")
        import(
            hoaA,
            "OPEN,A-01,2025-12-31,adjustment,Balance carried forward from 2025,100.00,",
            "D-Q1,A-01,2025-12-15,assessment,Q1 dues,450.00,2026-01-01",
            "P-Q1,A-01,2026-01-05,payment,Check payment,-450.00,",
            "F-1,A-01,2026-02-01,late_fee,Late fee,25.00,",
            "D-Q2,A-01,2026-03-15,assessment,Q2 dues,450.00,2026-04-01",
            "P-Q2,A-01,2026-04-03,payment,Check payment,-300.00,",
            "D-Q3,A-01,2026-06-15,assessment,Q3 dues,450.00,2026-07-01",
            "D-Q4,A-01,2026-09-15,assessment,Q4 dues,450.00,2026-10-01",
            "X-2,A-02,2026-01-05,payment,Advance payment,-200.00,",
        )
        import(hoaA, "F-1,A-01,2026-02-01,late_fee,Late fee,0,") // voided
        import(hoaB, "B-1,A-01,2026-01-01,assessment,Other HOA dues,999.00,")
    }

    private fun import(hoa: UUID, vararg rows: String) =
        ImportTransactions(db)(
            root, hoa, "f.csv", ("transaction_id,account,date,type,description,amount,due_date\n" + rows.joinToString("\n")).toByteArray(),
            commit = true, reason = "import",
        ).shouldBeInstanceOf<ImportTransactions.Result.Imported>()

    @AfterEach
    fun close() = db.close()

    private fun ApplicationTestBuilder.client(): HttpClient {
        application { seebrookModule(db, TestAuth.verifier, clock = clock) }
        return createClient { install(ContentNegotiation) { json() } }
    }

    private suspend fun HttpClient.becomeOwner(unit: Fixtures.Unit, uid: String, email: String): String {
        val token = TestAuth.token(uid, email)
        val invitation = IssueInvitation(db)(unit.hoaId, unit.propertyId, email).token
        post("/v1/invitations/accept") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(AcceptInvitationRequest(invitation))
        }.status shouldBe HttpStatusCode.OK
        return token
    }

    private fun userId(email: String): UUID =
        requireNotNull(db.dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT).where(USER_ACCOUNT.EMAIL.eq(email)).fetchSingle().value1())

    /** Rewrites an ownership's period, as if it had started and ended at these times. */
    private fun period(unit: Fixtures.Unit, email: String, started: String, ended: String?) {
        db.dsl.update(OWNERSHIP)
            .set(OWNERSHIP.STARTED_AT, OffsetDateTime.parse(started))
            .set(OWNERSHIP.ENDED_AT, ended?.let(OffsetDateTime::parse))
            .where(OWNERSHIP.PROPERTY_ID.eq(unit.propertyId)).and(OWNERSHIP.USER_ID.eq(userId(email)))
            .execute()
    }

    private suspend fun HttpClient.summary(token: String, unit: Fixtures.Unit): HttpResponse =
        get("/v1/properties/${unit.propertyId}/finance/summary") { bearerAuth(token) }

    private suspend fun HttpClient.history(token: String, unit: Fixtures.Unit, vararg params: Pair<String, Any>): HttpResponse =
        get("/v1/properties/${unit.propertyId}/finance/transactions") {
            bearerAuth(token)
            params.forEach { (k, v) -> parameter(k, v) }
        }

    @Test
    fun `an owner sees the balance with what is due and overdue, and the full history`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")

        val summary = http.summary(owner, unit1).body<AccountSummaryResponse>()
        summary shouldBe AccountSummaryResponse(
            propertyId = unit1.propertyId.toString(),
            currency = "USD",
            balanceCents = 115000,
            amountDueCents = 115000,
            // 100 + 450 + 450 + 450 past due (Q3 was due 1 July), 750 paid.
            overdueCents = 70000,
            notYetDueCents = 45000,
            creditCents = 0,
            nextDueDate = "2026-10-01",
            asOfDate = "2026-07-10",
            updatedAt = summary.updatedAt,
        )
        Instant.parse(summary.updatedAt)

        val page = http.history(owner, unit1).body<AccountTransactionsPageResponse>()
        page.carriedForward shouldBe null
        page.total shouldBe 7 // the voided late fee is left out
        page.transactions.map { Triple(it.reference, it.amountCents, it.balanceAfterCents) } shouldContainExactly listOf(
            Triple("D-Q4", 45000L, 115000L),
            Triple("D-Q3", 45000L, 70000L),
            Triple("P-Q2", -30000L, 25000L),
            Triple("D-Q2", 45000L, 55000L),
            Triple("P-Q1", -45000L, 10000L),
            Triple("OPEN", 10000L, 55000L),
            Triple("D-Q1", 45000L, 45000L),
        )
        with(page.transactions.first()) {
            type shouldBe "assessment"
            date shouldBe "2026-09-15"
            dueDate shouldBe "2026-10-01"
            description shouldBe "Q4 dues"
        }
        http.history(owner, unit1, "page" to 2, "pageSize" to 3).body<AccountTransactionsPageResponse>()
            .transactions.map { it.reference } shouldContainExactly listOf("D-Q2", "P-Q1", "OPEN")

        // A credit balance owes nothing.
        val other = http.becomeOwner(unit2, "uid-2", "two@example.test")
        with(http.summary(other, unit2).body<AccountSummaryResponse>()) {
            balanceCents shouldBe -20000
            amountDueCents shouldBe 0
            creditCents shouldBe 20000
            nextDueDate shouldBe null
        }
    }

    @Test
    fun `a new owner sees the balance but only their period, with earlier owners' history carried forward`() = testApplication {
        val http = client()
        val former = http.becomeOwner(unit1, "uid-old", "old@example.test")
        val coOwner = http.becomeOwner(unit1, "uid-co", "co@example.test")
        // The former owner owned it since 2020 and left on 31 March 2026 (noon in New York); the
        // co-owner owned alongside them from 2024 and stays.
        period(unit1, "old@example.test", "2020-01-01T00:00:00Z", "2026-03-31T16:00:00Z")
        period(unit1, "co@example.test", "2024-01-01T00:00:00Z", null)
        val buyer = http.becomeOwner(unit1, "uid-new", "new@example.test")

        val page = http.history(buyer, unit1).body<AccountTransactionsPageResponse>()
        page.carriedForward shouldBe CarriedForwardResponse(amountCents = 55000, throughDate = "2026-03-31")
        page.transactions.map { it.reference to it.balanceAfterCents } shouldContainExactly listOf(
            "D-Q4" to 115000L,
            "D-Q3" to 70000L,
            "P-Q2" to 25000L,
        )
        page.total shouldBe 3
        // The balance and what is due are the whole account's.
        http.summary(buyer, unit1).body<AccountSummaryResponse>().balanceCents shouldBe 115000

        // The co-owner shared the earlier period, so sees all of it.
        with(http.history(coOwner, unit1).body<AccountTransactionsPageResponse>()) {
            carriedForward shouldBe null
            total shouldBe 7
        }
        // The former owner no longer sees anything.
        http.summary(former, unit1).status shouldBe HttpStatusCode.NotFound
        http.history(former, unit1).status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `nobody else sees a property's finances`() = testApplication {
        val http = client()
        val ownerB = http.becomeOwner(unitB, "uid-b", "b@example.test")
        val ownerA = http.becomeOwner(unit2, "uid-a", "a@example.test")

        // Another HOA's owner, another unit's owner, an unknown or malformed ID: all the same 404.
        listOf(ownerB to unit1.propertyId.toString(), ownerA to unit1.propertyId.toString(), ownerA to UUID.randomUUID().toString(), ownerA to "nope")
            .forEach { (token, id) ->
                listOf("summary", "transactions").forEach { path ->
                    val response = http.get("/v1/properties/$id/finance/$path") { bearerAuth(token) }
                    response.status shouldBe HttpStatusCode.NotFound
                    response.body<ErrorResponse>().error shouldBe "not_found"
                }
            }
        // Their own HOA's account with the same spreadsheet ID is a different account.
        http.summary(ownerB, unitB).body<AccountSummaryResponse>().balanceCents shouldBe 99900

        // An owned unit without a finance account yet.
        val ownerC = http.becomeOwner(unmapped, "uid-c", "c@example.test")
        http.summary(ownerC, unmapped).let {
            it.status shouldBe HttpStatusCode.NotFound
            it.body<ErrorResponse>().error shouldBe "finance_unavailable"
        }

        listOf("page" to 0, "pageSize" to 51, "page" to "x").forEach { param ->
            http.history(ownerA, unit2, param).status shouldBe HttpStatusCode.BadRequest
        }
        http.get("/v1/properties/${unit2.propertyId}/finance/summary").status shouldBe HttpStatusCode.Unauthorized
        http.summary(TestAuth.token("uid-x", "x@example.test"), unit2).status shouldBe HttpStatusCode.Forbidden

        // Revoking ownership revokes finance access on the next request.
        db.transaction { Ownerships.end(it, hoaA, unit2.propertyId, listOf(userId("a@example.test"))) }
        http.summary(ownerA, unit2).status shouldBe HttpStatusCode.NotFound
    }
}
