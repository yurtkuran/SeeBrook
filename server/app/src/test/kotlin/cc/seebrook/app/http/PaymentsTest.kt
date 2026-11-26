package cc.seebrook.app.http

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.finance.Tables.LEDGER_ENTRY
import cc.seebrook.db.generated.finance.Tables.PAYMENT
import cc.seebrook.db.generated.finance.Tables.PAYMENT_EVENT
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.db.generated.property.Tables.OWNERSHIP
import cc.seebrook.finance.ConfigurePayments
import cc.seebrook.finance.FeeRule
import cc.seebrook.finance.ImportAccounts
import cc.seebrook.finance.ImportTransactions
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.ProviderPaymentStatus
import cc.seebrook.platform.payments.SimulatedPaymentProvider
import cc.seebrook.shared.api.AcceptInvitationRequest
import cc.seebrook.shared.api.AccountSummaryResponse
import cc.seebrook.shared.api.AccountTransactionsPageResponse
import cc.seebrook.shared.api.ErrorResponse
import cc.seebrook.shared.api.PaymentOptionResponse
import cc.seebrook.shared.api.PaymentQuoteRequest
import cc.seebrook.shared.api.PaymentQuoteResponse
import cc.seebrook.shared.api.PaymentResponse
import cc.seebrook.shared.api.PaymentsResponse
import cc.seebrook.shared.api.QuoteChangedResponse
import cc.seebrook.shared.api.StartPaymentRequest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * M5 one-time payments over HTTP (ED-43), with the simulated provider: the owner approves dues +
 * fee = total, only the dues credit the ledger and only when the provider confirms, replays and
 * repeats never pay or credit twice, and only active owners of the property can pay.
 */
class PaymentsTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val provider = SimulatedPaymentProvider("test-webhook-secret")
    private val providers = PaymentProviders(listOf(provider))
    private val hoaA = Fixtures.hoa(db, "hoa-a")
    private val hoaB = Fixtures.hoa(db, "hoa-b")
    private val unit1 = Fixtures.property(db, hoaA, "Unit 01")
    private val unit2 = Fixtures.property(db, hoaA, "Unit 02")
    private val unmapped = Fixtures.property(db, hoaA, "Unit 03")
    private val unitB = Fixtures.property(db, hoaB, "Unit 01")

    init {
        ImportAccounts(db)(root, hoaA, "unit_label,account\nUnit 01,A-01\nUnit 02,A-02\n".toByteArray(), commit = true, reason = "map")
            .shouldBeInstanceOf<ImportAccounts.Result.Imported>()
        ImportAccounts(db)(root, hoaB, "unit_label,account\nUnit 01,A-01\n".toByteArray(), commit = true, reason = "map")
        listOf(hoaA to "A-01", hoaA to "A-02", hoaB to "A-01").forEachIndexed { i, (hoa, account) ->
            ImportTransactions(db)(
                root, hoa, "f$i.csv",
                "transaction_id,account,date,type,description,amount,due_date\nD-$i,$account,2026-10-01,assessment,Q4 dues,350.00,2026-10-01\n".toByteArray(),
                commit = true, reason = "dues",
            ).shouldBeInstanceOf<ImportTransactions.Result.Imported>()
        }
        // Channel Harbor's beta policy: the owner pays the card fee ($5 here), the HOA absorbs ACH.
        configure(hoaA, "sim_hoa_a")
        configure(hoaB, "sim_hoa_b")
    }

    private fun configure(hoa: UUID, merchant: String, suspended: Boolean = false) =
        ConfigurePayments(db, providers)(
            root, hoa,
            ConfigurePayments.Change(SimulatedPaymentProvider.NAME, merchant, mapOf(PaymentMethod.CARD to FeeRule(0, 500), PaymentMethod.ACH to FeeRule.NONE), suspended),
            commit = true, reason = "setup",
        ).shouldBeInstanceOf<ConfigurePayments.Result.Planned>()

    @AfterEach
    fun close() = db.close()

    private fun ApplicationTestBuilder.client(): HttpClient {
        application { seebrookModule(db, TestAuth.verifier, payments = providers) }
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

    private suspend fun HttpClient.quote(token: String, unit: Fixtures.Unit, cents: Long): HttpResponse =
        post("/v1/properties/${unit.propertyId}/payments/quote") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PaymentQuoteRequest(cents))
        }

    private suspend fun HttpClient.start(token: String, unit: Fixtures.Unit, request: StartPaymentRequest): HttpResponse =
        post("/v1/properties/${unit.propertyId}/payments") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private fun card(cents: Long = 35_000, fee: Long = 500, key: String = "key-${UUID.randomUUID()}") =
        StartPaymentRequest("card", cents, fee, cents + fee, key)

    private suspend fun HttpClient.summary(token: String, unit: Fixtures.Unit) =
        get("/v1/properties/${unit.propertyId}/finance/summary") { bearerAuth(token) }.body<AccountSummaryResponse>()

    /** Sends the provider's signed webhook for a payment, as the provider would. */
    private suspend fun HttpClient.providerEvent(
        paymentId: String,
        status: ProviderPaymentStatus,
        eventId: String = "evt-${UUID.randomUUID()}",
        amountCents: Long? = null,
        merchant: String? = null,
        signature: String? = null,
    ): HttpResponse {
        val row = db.dsl.selectFrom(PAYMENT).where(PAYMENT.ID.eq(UUID.fromString(paymentId))).fetchSingle()
        val (payload, signed) = provider.event(merchant ?: row.merchantRef, row.providerPaymentId, status, amountCents ?: row.totalCents, eventId)
        return post("/v1/webhooks/payments/simulated") {
            header(SimulatedPaymentProvider.SIGNATURE_HEADER, signature ?: signed)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    private fun ledgerPayments(paymentId: String): List<Long> =
        db.dsl.select(LEDGER_ENTRY.AMOUNT_CENTS).from(LEDGER_ENTRY).where(LEDGER_ENTRY.PAYMENT_ID.eq(UUID.fromString(paymentId))).fetch(LEDGER_ENTRY.AMOUNT_CENTS)

    private fun status(paymentId: String) = db.dsl.select(PAYMENT.STATUS).from(PAYMENT).where(PAYMENT.ID.eq(UUID.fromString(paymentId))).fetchSingle().value1()

    @Test
    fun `$350 dues by card with a $5 fee credit $350, only once the provider confirms, and a replay changes nothing`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")

        http.quote(owner, unit1, 35_000).body<PaymentQuoteResponse>() shouldBe PaymentQuoteResponse(
            propertyId = unit1.propertyId.toString(), currency = "USD", amountCents = 35_000, amountDueCents = 35_000,
            options = listOf(PaymentOptionResponse("card", 500, 35_500), PaymentOptionResponse("ach", 0, 35_000)),
        )
        val started = http.start(owner, unit1, card()).also { it.status shouldBe HttpStatusCode.Created }.body<PaymentResponse>()
        started.status shouldBe "created"
        started.totalCents shouldBe 35_500
        started.provider shouldBe "simulated"
        started.clientParams shouldBe mapOf("simulated" to "true")
        started.reference.matches(Regex("^PAY-[0-9A-Z]{8}$")) shouldBe true

        // Submitted is not paid: the balance waits for the provider's confirmation.
        http.summary(owner, unit1).balanceCents shouldBe 35_000
        http.providerEvent(started.id, ProviderPaymentStatus.PROCESSING).status shouldBe HttpStatusCode.OK
        http.summary(owner, unit1).let {
            it.balanceCents shouldBe 35_000
            it.pendingPaymentsCents shouldBe 35_000
        }

        val succeeded = "evt-succeeded-1"
        http.providerEvent(started.id, ProviderPaymentStatus.SUCCEEDED, succeeded).status shouldBe HttpStatusCode.OK
        ledgerPayments(started.id) shouldContainExactly listOf(-35_000L) // the dues only, never the fee
        http.summary(owner, unit1).let {
            it.balanceCents shouldBe 0
            it.pendingPaymentsCents shouldBe 0
        }
        http.get("/v1/properties/${unit1.propertyId}/finance/transactions") { bearerAuth(owner) }.body<AccountTransactionsPageResponse>()
            .transactions.first().let {
                it.reference shouldBe started.reference
                it.type shouldBe "payment"
                it.description shouldBe "Online payment (card)"
                it.amountCents shouldBe -35_000
                it.balanceAfterCents shouldBe 0
            }

        // The provider delivers the same event again, and a late "processing" arrives: nothing changes.
        http.providerEvent(started.id, ProviderPaymentStatus.SUCCEEDED, succeeded).status shouldBe HttpStatusCode.OK
        http.providerEvent(started.id, ProviderPaymentStatus.PROCESSING).status shouldBe HttpStatusCode.OK
        http.providerEvent(started.id, ProviderPaymentStatus.SUCCEEDED).status shouldBe HttpStatusCode.OK
        ledgerPayments(started.id) shouldContainExactly listOf(-35_000L)
        status(started.id) shouldBe "succeeded"
        db.dsl.select(PAYMENT_EVENT.OUTCOME).from(PAYMENT_EVENT).orderBy(PAYMENT_EVENT.RECEIVED_AT, PAYMENT_EVENT.ID).fetch(PAYMENT_EVENT.OUTCOME) shouldContainExactly
            listOf("applied", "applied", "unchanged", "unchanged")

        // The ledger still equals imported transactions minus settled payments, for every account.
        val ledger = db.dsl.select(LEDGER_ENTRY.AMOUNT_CENTS).from(LEDGER_ENTRY).where(LEDGER_ENTRY.HOA_ID.eq(hoaA)).fetch(LEDGER_ENTRY.AMOUNT_CENTS).sum()
        ledger shouldBe 35_000L // Unit 02 still owes its $350
        http.get("/v1/properties/${unit1.propertyId}/payments") { bearerAuth(owner) }.body<PaymentsResponse>().payments.single().let {
            it.status shouldBe "succeeded"
            it.clientParams shouldBe null
        }
    }

    @Test
    fun `a repeated submission is the same payment, and a changed fee or amount needs reconfirmation`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")
        val request = card(key = "approval-0001")

        val first = http.start(owner, unit1, request).body<PaymentResponse>()
        val again = http.start(owner, unit1, request).also { it.status shouldBe HttpStatusCode.Created }.body<PaymentResponse>()
        again.id shouldBe first.id
        again.clientParams shouldBe first.clientParams
        db.dsl.fetchCount(PAYMENT) shouldBe 1

        http.start(owner, unit1, request.copy(amountCents = 20_000, totalCents = 20_500)).let {
            it.status shouldBe HttpStatusCode.Conflict
            it.body<ErrorResponse>().error shouldBe "idempotency_conflict"
        }
        // The app showed a fee the HOA no longer charges: nothing is charged, here is the new quote.
        http.start(owner, unit1, card(fee = 0)).let {
            it.status shouldBe HttpStatusCode.Conflict
            it.body<QuoteChangedResponse>().quote.options.first { o -> o.method == "card" } shouldBe PaymentOptionResponse("card", 500, 35_500)
        }
        // ACH: the HOA absorbs the fee. Partial and advance payments are allowed from $1.
        http.start(owner, unit1, StartPaymentRequest("ach", 10_000, 0, 10_000, "approval-0002")).status shouldBe HttpStatusCode.Created
        http.start(owner, unit1, StartPaymentRequest("ach", 99, 0, 99, "approval-0003")).status shouldBe HttpStatusCode.BadRequest
        http.start(owner, unit1, StartPaymentRequest("cheque", 10_000, 0, 10_000, "approval-0004")).status shouldBe HttpStatusCode.BadRequest
        http.start(owner, unit1, card(key = "short")).status shouldBe HttpStatusCode.BadRequest
        db.dsl.fetchCount(PAYMENT) shouldBe 2
        db.dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.PAYMENT_ID.isNotNull) shouldBe 0
    }

    @Test
    fun `failed payments credit nothing, and forged or mismatched events are refused`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")
        val payment = http.start(owner, unit1, card()).body<PaymentResponse>()

        http.providerEvent(payment.id, ProviderPaymentStatus.SUCCEEDED, signature = "t=1,v1=forged").status shouldBe HttpStatusCode.BadRequest
        http.providerEvent(payment.id, ProviderPaymentStatus.SUCCEEDED, amountCents = 1_000).status shouldBe HttpStatusCode.OK
        http.providerEvent(payment.id, ProviderPaymentStatus.SUCCEEDED, merchant = "sim_hoa_b").status shouldBe HttpStatusCode.OK
        http.post("/v1/webhooks/payments/unknown") { setBody("{}") }.status shouldBe HttpStatusCode.NotFound
        ledgerPayments(payment.id) shouldBe emptyList()
        db.dsl.select(PAYMENT_EVENT.OUTCOME).from(PAYMENT_EVENT).orderBy(PAYMENT_EVENT.RECEIVED_AT, PAYMENT_EVENT.ID).fetch(PAYMENT_EVENT.OUTCOME) shouldContainExactly
            listOf("mismatch", "unmatched")

        http.providerEvent(payment.id, ProviderPaymentStatus.FAILED).status shouldBe HttpStatusCode.OK
        status(payment.id) shouldBe "failed"
        // The owner sees why in words, not the provider's code (ED-64).
        http.get("/v1/properties/${unit1.propertyId}/payments") { bearerAuth(owner) }.body<PaymentsResponse>().payments.single().let {
            it.failureMessage shouldBe "Your card was declined. Try another card or contact your card's bank."
        }
        http.summary(owner, unit1).balanceCents shouldBe 35_000
        // The owner retried with another card at the provider, and it went through.
        http.providerEvent(payment.id, ProviderPaymentStatus.SUCCEEDED).status shouldBe HttpStatusCode.OK
        http.summary(owner, unit1).balanceCents shouldBe 0
    }

    @Test
    fun `only active owners of the property can pay, and a suspended HOA takes no payments`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")
        val neighbor = http.becomeOwner(unit2, "uid-2", "two@example.test")
        val elsewhere = http.becomeOwner(unitB, "uid-3", "three@example.test")

        fun error(response: HttpResponse) = response.status
        error(http.quote(neighbor, unit1, 35_000)) shouldBe HttpStatusCode.NotFound
        error(http.start(neighbor, unit1, card())) shouldBe HttpStatusCode.NotFound
        error(http.quote(elsewhere, unit1, 35_000)) shouldBe HttpStatusCode.NotFound
        error(http.get("/v1/properties/${unit1.propertyId}/payments") { bearerAuth(elsewhere) }) shouldBe HttpStatusCode.NotFound
        http.post("/v1/properties/${unit1.propertyId}/payments/quote") {
            contentType(ContentType.Application.Json)
            setBody(PaymentQuoteRequest(35_000))
        }.status shouldBe HttpStatusCode.Unauthorized

        val mapless = http.becomeOwner(unmapped, "uid-4", "four@example.test")
        http.quote(mapless, unmapped, 35_000).body<ErrorResponse>().error shouldBe "finance_unavailable"

        configure(hoaA, "sim_hoa_a", suspended = true)
        http.quote(owner, unit1, 35_000).let {
            it.status shouldBe HttpStatusCode.ServiceUnavailable
            it.body<ErrorResponse>().error shouldBe "payments_unavailable"
        }
        http.summary(owner, unit1).balanceCents shouldBe 35_000 // history stays readable
        configure(hoaA, "sim_hoa_a")
        http.start(owner, unit1, card()).status shouldBe HttpStatusCode.Created

        // The owner sells: their access, and with it paying, ends at once (GA-07).
        val userId = db.dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT).where(USER_ACCOUNT.EMAIL.eq("one@example.test")).fetchSingle().value1()
        db.dsl.update(OWNERSHIP).set(OWNERSHIP.ENDED_AT, OffsetDateTime.now()).where(OWNERSHIP.USER_ID.eq(userId)).execute()
        http.quote(owner, unit1, 35_000).status shouldBe HttpStatusCode.NotFound
        http.start(owner, unit1, card()).status shouldBe HttpStatusCode.NotFound
    }
}
