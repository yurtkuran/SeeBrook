package cc.seebrook.app.http

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.db.generated.finance.Tables.LEDGER_ENTRY
import cc.seebrook.db.generated.finance.Tables.PAYMENT
import cc.seebrook.db.generated.finance.Tables.RECURRING_CHARGE
import cc.seebrook.db.generated.finance.Tables.RECURRING_PLAN
import cc.seebrook.db.generated.jobs.Tables.OUTBOX_JOB
import cc.seebrook.finance.ConfigurePayments
import cc.seebrook.finance.ConfigureRecurringSchedule
import cc.seebrook.finance.FeeRule
import cc.seebrook.finance.Frequency
import cc.seebrook.finance.ImportAccounts
import cc.seebrook.finance.OnOwnershipEnded
import cc.seebrook.finance.PaymentEmailJob
import cc.seebrook.finance.RecurringEmailJob
import cc.seebrook.finance.RunRecurringCharges
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.identity.TransferOwnership
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.jobs.JobRunner
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProvider
import cc.seebrook.platform.payments.PaymentProviderException
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.ProviderPayment
import cc.seebrook.platform.payments.ProviderPaymentStatus
import cc.seebrook.platform.payments.SavedChargeRequest
import cc.seebrook.platform.payments.SimulatedPaymentProvider
import cc.seebrook.property.OwnershipEndedJob
import cc.seebrook.shared.api.AcceptInvitationRequest
import cc.seebrook.shared.api.CompleteMethodSetupRequest
import cc.seebrook.shared.api.ErrorResponse
import cc.seebrook.shared.api.FrequencyOptionResponse
import cc.seebrook.shared.api.MethodSetupResponse
import cc.seebrook.shared.api.RecurringPlanResponse
import cc.seebrook.shared.api.RecurringQuoteChangedResponse
import cc.seebrook.shared.api.RecurringResponse
import cc.seebrook.shared.api.SavedMethodResponse
import cc.seebrook.shared.api.SetUpRecurringPlanRequest
import cc.seebrook.shared.api.StartMethodSetupRequest
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Recurring payments (M6, ED-59) with the simulated provider: saving a method, a plan with the
 * exact figures the owner approved, one charge per plan and due date (never twice, never retried
 * after a failure), holds when the fee or method changes, paused payments, and cancellation when
 * the ownership ends (GA-08).
 */
class RecurringTest {
    /** A clock the test moves forward. */
    private class MovableClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = View(this, zone)
        override fun instant(): Instant = now

        private class View(val base: MovableClock, private val zone: ZoneId) : Clock() {
            override fun getZone() = zone
            override fun withZone(zone: ZoneId): Clock = View(base, zone)
            override fun instant(): Instant = base.now
        }
    }

    /** The simulated provider, with outages scripted by the test. */
    private class ScriptedProvider(private val sim: SimulatedPaymentProvider) : PaymentProvider by sim {
        var down = false
        var charges = 0

        override fun chargeSaved(request: SavedChargeRequest): ProviderPayment {
            if (down) throw PaymentProviderException("simulated outage")
            charges++
            return sim.chargeSaved(request)
        }
    }

    private val db: Database = TestPostgres.freshDatabase()
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val sim = SimulatedPaymentProvider("test-webhook-secret")
    private val provider = ScriptedProvider(sim)
    private val providers = PaymentProviders(listOf(provider))
    private val clock = MovableClock(Instant.parse("2026-12-15T15:00:00Z"))
    private val hoaA = Fixtures.hoa(db, "hoa-a")
    private val hoaB = Fixtures.hoa(db, "hoa-b")
    private val unit1 = Fixtures.property(db, hoaA, "Unit 01")
    private val unitB = Fixtures.property(db, hoaB, "Unit 01")
    private val run = RunRecurringCharges(db, providers, clock)

    init {
        ImportAccounts(db)(root, hoaA, "unit_label,account\nUnit 01,A-01\n".toByteArray(), commit = true, reason = "map")
            .shouldBeInstanceOf<ImportAccounts.Result.Imported>()
        ImportAccounts(db)(root, hoaB, "unit_label,account\nUnit 01,B-01\n".toByteArray(), commit = true, reason = "map")
        configure(hoaA, "sim_hoa_a", cardFee = FeeRule(0, 500))
        configure(hoaB, "sim_hoa_b", cardFee = FeeRule(0, 500))
        // Channel Harbor's schedule: monthly dues on the 1st; owners may choose any frequency.
        ConfigureRecurringSchedule(db)(root, hoaA, 1, 1, Frequency.entries.toSet(), "test")
            .shouldBeInstanceOf<ConfigureRecurringSchedule.Result.Set>()
        ConfigureRecurringSchedule(db)(root, hoaB, 1, 1, Frequency.entries.toSet(), "test")
    }

    private fun configure(hoa: UUID, merchant: String, cardFee: FeeRule, suspended: Boolean = false) =
        ConfigurePayments(db, providers)(
            root, hoa,
            ConfigurePayments.Change(SimulatedPaymentProvider.NAME, merchant, mapOf(PaymentMethod.CARD to cardFee, PaymentMethod.ACH to FeeRule.NONE), suspended),
            commit = true, reason = "setup",
        ).shouldBeInstanceOf<ConfigurePayments.Result.Planned>()

    @AfterEach
    fun close() = db.close()

    private fun ApplicationTestBuilder.client(): HttpClient {
        application { seebrookModule(db, TestAuth.verifier, clock = clock, payments = providers) }
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

    private fun path(unit: Fixtures.Unit) = "/v1/properties/${unit.propertyId}/recurring"

    private suspend fun HttpClient.overview(token: String, unit: Fixtures.Unit) = get(path(unit)) { bearerAuth(token) }

    private suspend fun HttpClient.saveMethod(token: String, unit: Fixtures.Unit, method: String = "card"): SavedMethodResponse {
        val setup = post("${path(unit)}/methods/setup") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(StartMethodSetupRequest(method))
        }.also { it.status shouldBe HttpStatusCode.Created }.body<MethodSetupResponse>()
        return post("${path(unit)}/methods/complete") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CompleteMethodSetupRequest(setup.setupId))
        }.also { it.status shouldBe HttpStatusCode.Created }.body<SavedMethodResponse>()
    }

    private suspend fun HttpClient.setUpPlan(
        token: String,
        unit: Fixtures.Unit,
        methodId: String,
        frequency: String = "monthly",
        dues: Long = 45_000,
        fee: Long = 500,
    ): HttpResponse = put("${path(unit)}/plan") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(SetUpRecurringPlanRequest(methodId, frequency, dues, fee, dues + fee))
    }

    private suspend fun HttpClient.planAction(token: String, unit: Fixtures.Unit, action: String) =
        post("${path(unit)}/plan/$action") { bearerAuth(token) }

    /** Sends the provider's signed webhook for a payment, as the provider would. */
    private suspend fun HttpClient.providerEvent(paymentId: UUID, status: ProviderPaymentStatus, eventId: String = "evt-${UUID.randomUUID()}"): HttpResponse {
        val row = db.dsl.selectFrom(PAYMENT).where(PAYMENT.ID.eq(paymentId)).fetchSingle()
        val (payload, signed) = sim.event(row.merchantRef, row.providerPaymentId, status, row.totalCents, eventId)
        return post("/v1/webhooks/payments/simulated") {
            header(SimulatedPaymentProvider.SIGNATURE_HEADER, signed)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    private fun at(date: String) {
        clock.now = Instant.parse("${date}T15:00:00Z") // 10:00 in New York
    }

    private fun recurringPayments(planId: String) =
        db.dsl.selectFrom(PAYMENT).where(PAYMENT.RECURRING_PLAN_ID.eq(UUID.fromString(planId))).orderBy(PAYMENT.CREATED_AT).fetch()

    private fun charges(planId: String) =
        db.dsl.selectFrom(RECURRING_CHARGE).where(RECURRING_CHARGE.PLAN_ID.eq(UUID.fromString(planId))).orderBy(RECURRING_CHARGE.SCHEDULED_DATE).fetch()

    private fun planStatus(planId: String) =
        db.dsl.select(RECURRING_PLAN.STATUS, RECURRING_PLAN.STATUS_REASON).from(RECURRING_PLAN).where(RECURRING_PLAN.ID.eq(UUID.fromString(planId)))
            .fetchSingle().let { it.value1() to it.value2() }

    @Test
    fun `a monthly card plan charges once on each due date's business day, credits the dues once, and a failure is not retried`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")

        val method = http.saveMethod(owner, unit1)
        method.label shouldBe "Test card •••• 4242"
        val overview = http.overview(owner, unit1).body<RecurringResponse>()
        overview.available shouldBe true
        overview.methods shouldBe listOf(method)
        // January 1, 2027 is New Year's Day (a Friday): charged Monday the 4th.
        overview.options shouldBe listOf(
            FrequencyOptionResponse("monthly", "2027-01-01", "2027-01-04"),
            FrequencyOptionResponse("quarterly", "2027-01-01", "2027-01-04"),
            FrequencyOptionResponse("annual", "2027-01-01", "2027-01-04"),
        )

        val plan = http.setUpPlan(owner, unit1, method.id).also { it.status shouldBe HttpStatusCode.Created }.body<RecurringPlanResponse>()
        plan.status shouldBe "active"
        plan.totalCents shouldBe 45_500
        plan.nextChargeDate shouldBe "2027-01-04"
        db.dsl.fetchCount(OUTBOX_JOB, OUTBOX_JOB.KIND.eq(RecurringEmailJob.KIND)) shouldBe 1 // set-up confirmation

        // Nothing before the charge day.
        run().started shouldBe 0
        at("2027-01-01")
        run().started shouldBe 0
        at("2027-01-04")
        run().let { it.started shouldBe 1; it.submitted shouldBe 1 }
        // The same day again: no second charge, nothing resubmitted.
        run().let { it.started shouldBe 0; it.submitted shouldBe 0 }
        provider.charges shouldBe 1

        val january = recurringPayments(plan.id).single()
        january.totalCents shouldBe 45_500
        january.duesCents shouldBe 45_000
        january.providerPaymentId shouldStartWith "sim_pay_"
        january.idempotencyKey shouldBe "recurring-${plan.id}-2027-01-01"
        http.providerEvent(january.id, ProviderPaymentStatus.SUCCEEDED, eventId = "evt-jan").status shouldBe HttpStatusCode.OK
        http.providerEvent(january.id, ProviderPaymentStatus.SUCCEEDED, eventId = "evt-jan").status shouldBe HttpStatusCode.OK // replay
        db.dsl.select(LEDGER_ENTRY.AMOUNT_CENTS).from(LEDGER_ENTRY).where(LEDGER_ENTRY.PAYMENT_ID.eq(january.id))
            .fetch(LEDGER_ENTRY.AMOUNT_CENTS) shouldBe listOf(-45_000L)
        http.overview(owner, unit1).body<RecurringResponse>().plan?.nextChargeDate shouldBe "2027-02-01"

        // February: the charge fails. It is not retried, on that day or later; March charges as usual.
        at("2027-02-01")
        run().started shouldBe 1
        val february = recurringPayments(plan.id)[1]
        http.providerEvent(february.id, ProviderPaymentStatus.FAILED).status shouldBe HttpStatusCode.OK
        run().started shouldBe 0
        at("2027-02-02")
        run().let { it.started shouldBe 0; it.submitted shouldBe 0 }
        db.dsl.fetchCount(OUTBOX_JOB, OUTBOX_JOB.KIND.eq(PaymentEmailJob.KIND).and(OUTBOX_JOB.DEDUPE_KEY.contains("failed"))) shouldBe 1
        at("2027-03-01")
        run().started shouldBe 1
        charges(plan.id).map { it.scheduledDate to it.outcome } shouldBe listOf(
            LocalDate.parse("2027-01-01") to "started",
            LocalDate.parse("2027-02-01") to "started",
            LocalDate.parse("2027-03-01") to "started",
        )
        recurringPayments(plan.id).map { it.status } shouldBe listOf("succeeded", "failed", "created")
        planStatus(plan.id) shouldBe ("active" to null)
        db.dsl.select(EVENT.ACTION).from(EVENT).where(EVENT.TARGET_ID.eq(UUID.fromString(plan.id))).fetch(EVENT.ACTION)
            .shouldContainAll("finance.recurring_plan_created", "finance.recurring_charge_started")
    }

    @Test
    fun `setting up needs a recent sign-in and the exact figures, and only the owner's own methods and properties`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")
        val stale = TestAuth.token("uid-1", "one@example.test", signedInAgo = Duration.ofMinutes(20))
        val other = http.becomeOwner(unitB, "uid-2", "two@example.test")

        http.post("${path(unit1)}/methods/setup") {
            bearerAuth(stale)
            contentType(ContentType.Application.Json)
            setBody(StartMethodSetupRequest("card"))
        }.apply {
            status shouldBe HttpStatusCode.Forbidden
            body<ErrorResponse>().error shouldBe "reauthentication_required"
        }
        val method = http.saveMethod(owner, unit1)
        http.setUpPlan(stale, unit1, method.id).status shouldBe HttpStatusCode.Forbidden

        // A fee the owner did not see: nothing is set up; the right figures come back.
        http.setUpPlan(owner, unit1, method.id, fee = 0).apply {
            status shouldBe HttpStatusCode.Conflict
            body<RecurringQuoteChangedResponse>() shouldBe RecurringQuoteChangedResponse("quote_changed", 500, 45_500)
        }
        http.setUpPlan(owner, unit1, method.id, frequency = "weekly").status shouldBe HttpStatusCode.BadRequest

        // Another HOA's owner can neither see this property nor use this owner's method.
        http.overview(other, unit1).status shouldBe HttpStatusCode.NotFound
        val otherMethod = http.saveMethod(other, unitB)
        http.setUpPlan(owner, unit1, otherMethod.id).apply {
            status shouldBe HttpStatusCode.NotFound
            body<ErrorResponse>().error shouldBe "method_not_found"
        }
        http.delete("${path(unitB)}/methods/${otherMethod.id}") { bearerAuth(owner) }.status shouldBe HttpStatusCode.NotFound

        val plan = http.setUpPlan(owner, unit1, method.id).body<RecurringPlanResponse>()
        // Pausing and canceling never need a fresh sign-in; resuming does.
        http.planAction(stale, unit1, "pause").body<RecurringPlanResponse>().status shouldBe "paused"
        http.planAction(stale, unit1, "resume").status shouldBe HttpStatusCode.Forbidden
        http.planAction(owner, unit1, "resume").body<RecurringPlanResponse>().status shouldBe "active"
        // A method in use stays; once the plan is canceled it can be removed.
        http.delete("${path(unit1)}/methods/${method.id}") { bearerAuth(owner) }.status shouldBe HttpStatusCode.Conflict
        http.planAction(stale, unit1, "cancel").body<RecurringPlanResponse>().status shouldBe "canceled"
        http.delete("${path(unit1)}/methods/${method.id}") { bearerAuth(owner) }.status shouldBe HttpStatusCode.NoContent
        http.overview(owner, unit1).body<RecurringResponse>().let { it.plan shouldBe null; it.methods shouldBe emptyList() }
        planStatus(plan.id) shouldBe ("canceled" to "owner")
    }

    @Test
    fun `a fee change holds the plan until the owner approves the new total, and never charges more`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")
        val method = http.saveMethod(owner, unit1)
        val plan = http.setUpPlan(owner, unit1, method.id).body<RecurringPlanResponse>()

        configure(hoaA, "sim_hoa_a", cardFee = FeeRule(0, 700))
        at("2027-01-04")
        run().let { it.started shouldBe 0; it.skipped shouldBe 1 }
        recurringPayments(plan.id).size shouldBe 0
        planStatus(plan.id) shouldBe ("needs_approval" to "fee_changed")
        charges(plan.id).single().skipReason shouldBe "fee_changed"
        db.dsl.fetchCount(OUTBOX_JOB, OUTBOX_JOB.KIND.eq(RecurringEmailJob.KIND).and(OUTBOX_JOB.DEDUPE_KEY.contains("needs_approval"))) shouldBe 1

        // Resuming is not approving: the owner approves the new figures.
        http.planAction(owner, unit1, "resume").body<ErrorResponse>().error shouldBe "plan_needs_approval"
        at("2027-01-10")
        val approved = http.setUpPlan(owner, unit1, method.id, fee = 700).also { it.status shouldBe HttpStatusCode.OK }.body<RecurringPlanResponse>()
        approved.status shouldBe "active"
        approved.totalCents shouldBe 45_700
        approved.nextDueDate shouldBe "2027-02-01"
        at("2027-02-01")
        run().started shouldBe 1
        recurringPayments(plan.id).single().totalCents shouldBe 45_700
    }

    @Test
    fun `while the HOA's payments are paused a due charge waits a few days, then is skipped and the plan goes on`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")
        val plan = http.setUpPlan(owner, unit1, http.saveMethod(owner, unit1).id).body<RecurringPlanResponse>()

        configure(hoaA, "sim_hoa_a", cardFee = FeeRule(0, 500), suspended = true)
        at("2027-01-04")
        run().waiting shouldBe 1
        at("2027-01-09")
        run().waiting shouldBe 1
        at("2027-01-10")
        run().skipped shouldBe 1
        charges(plan.id).single().skipReason shouldBe "payments_unavailable"
        planStatus(plan.id) shouldBe ("active" to null)
        recurringPayments(plan.id).size shouldBe 0

        configure(hoaA, "sim_hoa_a", cardFee = FeeRule(0, 500))
        at("2027-02-01")
        run().started shouldBe 1
    }

    @Test
    fun `a charge decided during a provider outage is submitted once the provider answers, exactly once`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")
        val plan = http.setUpPlan(owner, unit1, http.saveMethod(owner, unit1).id).body<RecurringPlanResponse>()

        provider.down = true
        at("2027-01-04")
        run().let { it.started shouldBe 1; it.submitted shouldBe 0 }
        recurringPayments(plan.id).single().let { it.status shouldBe "created"; it.providerPaymentId shouldBe null }

        provider.down = false
        at("2027-01-05")
        run().let { it.started shouldBe 0; it.submitted shouldBe 1 }
        run().submitted shouldBe 0
        provider.charges shouldBe 1
        recurringPayments(plan.id).single().providerPaymentId shouldNotBe null
    }

    @Test
    fun `when the ownership ends the departing owner's plan is canceled and never charged`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")
        val plan = http.setUpPlan(owner, unit1, http.saveMethod(owner, unit1).id).body<RecurringPlanResponse>()

        TransferOwnership(db)(root, hoaA, "Unit 01", listOf("new@example.test"), commit = true, reason = "sold")
            .shouldBeInstanceOf<TransferOwnership.Result.Transferred>()
        // Even before the queued cancellation runs, the charge run refuses to charge a former owner.
        at("2027-01-04")
        run().let { it.canceled shouldBe 1; it.started shouldBe 0 }
        planStatus(plan.id) shouldBe ("canceled" to "ownership_ended")
        // The queued cancellation then finds nothing left to do.
        JobRunner(db, mapOf(OwnershipEndedJob.KIND to OnOwnershipEnded(db))).drain() shouldBe 1
        recurringPayments(plan.id).size shouldBe 0
    }

    @Test
    fun `the queued cancellation ends the plan right after the transfer`() = testApplication {
        val http = client()
        val owner = http.becomeOwner(unit1, "uid-1", "one@example.test")
        val plan = http.setUpPlan(owner, unit1, http.saveMethod(owner, unit1).id).body<RecurringPlanResponse>()

        TransferOwnership(db)(root, hoaA, "Unit 01", listOf("new@example.test"), commit = true, reason = "sold")
        db.dsl.fetchCount(OUTBOX_JOB, OUTBOX_JOB.KIND.eq(OwnershipEndedJob.KIND)) shouldBe 1
        JobRunner(db, mapOf(OwnershipEndedJob.KIND to OnOwnershipEnded(db))).drain() shouldBe 1
        planStatus(plan.id) shouldBe ("canceled" to "ownership_ended")
        db.dsl.fetchCount(OUTBOX_JOB, OUTBOX_JOB.KIND.eq(RecurringEmailJob.KIND).and(OUTBOX_JOB.DEDUPE_KEY.contains("canceled"))) shouldBe 1
    }
}
