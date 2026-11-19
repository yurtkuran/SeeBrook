package cc.seebrook.app

import cc.seebrook.app.cli.FinanceCommands
import cc.seebrook.app.cli.PaymentCommands
import cc.seebrook.app.cli.RegisterCommands
import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.app.support.TestWorkbooks
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.db.generated.finance.Tables.ACCOUNT
import cc.seebrook.db.generated.finance.Tables.LEDGER_ENTRY
import cc.seebrook.db.generated.finance.Tables.PAYMENT
import cc.seebrook.db.generated.finance.Tables.PAYMENT_EVENT
import cc.seebrook.db.generated.jobs.Tables.OUTBOX_JOB
import cc.seebrook.finance.ConfigurePayments
import cc.seebrook.finance.FeeRule
import cc.seebrook.finance.GetAccountSummary
import cc.seebrook.finance.GetPaymentSettings
import cc.seebrook.finance.HandlePaymentEvent
import cc.seebrook.finance.InitiatePayment
import cc.seebrook.finance.MonitorPayments
import cc.seebrook.finance.OnlinePayments
import cc.seebrook.finance.PaymentEmailJob
import cc.seebrook.finance.QuotePayment
import cc.seebrook.identity.AcceptInvitation
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.payments.CreatePaymentRequest
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProvider
import cc.seebrook.platform.payments.PaymentProviderException
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.ProviderPaymentSnapshot
import cc.seebrook.platform.payments.ProviderPaymentStatus
import cc.seebrook.platform.payments.SimulatedPaymentProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * ED-46: payments whose webhook was lost are made good by asking the provider, through the same
 * once-only path as webhooks; a provider that stops answering pauses that HOA's payments until it
 * answers again, while an administrator's hold stays until lifted.
 */
class PaymentReconciliationTest {
    /** The simulated provider, with lookups and outages scripted by the test. */
    private class ScriptedProvider(private val sim: SimulatedPaymentProvider) : PaymentProvider by sim {
        val states = mutableMapOf<String, ProviderPaymentSnapshot>()
        var down = false
        var lookups = 0

        override fun createPayment(request: CreatePaymentRequest) =
            if (down) throw PaymentProviderException("simulated outage") else sim.createPayment(request)

        override fun lookupPayment(merchantRef: String, providerPaymentId: String): ProviderPaymentSnapshot {
            lookups++
            if (down) throw PaymentProviderException("simulated outage")
            return states[providerPaymentId] ?: sim.lookupPayment(merchantRef, providerPaymentId)
        }

        override fun checkAvailable(merchantRef: String) {
            if (down) throw PaymentProviderException("simulated outage")
        }
    }

    private val db = TestPostgres.freshDatabase()
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val sim = SimulatedPaymentProvider("reconcile-test-secret")
    private val provider = ScriptedProvider(sim)
    private val providers = PaymentProviders(listOf(provider))
    private val handle = HandlePaymentEvent(db, providers)
    private val hoa = Fixtures.hoa(db, "hoa-x")
    private val otherHoa = Fixtures.hoa(db, "hoa-y")
    private val units = listOf("A1", "A2", "B1")
    private val properties = units.associateWith { Fixtures.property(db, hoa, it) }
    private val owners = mutableMapOf<String, UserActor>()

    /** Later than now, so payments look settled long enough to be checked. */
    private fun later(hours: Long) = Clock.offset(Clock.systemUTC(), Duration.ofHours(hours))

    init {
        val dir = Files.createTempDirectory("reconcile")
        val workbook = dir.resolve("base.xlsx").also { Files.write(it, TestWorkbooks.build(TestWorkbooks.Spec(units = units))) }
        val out = dir.resolve("base")
        RegisterCommands.convert(listOf("--file", workbook.toString(), "--year", "2026", "--out", out.toString()))
        val who = listOf("--operator", "root@example.test", "--hoa", "hoa-x")
        FinanceCommands(db).importAccounts(who + listOf("--file", out.resolve("accounts.csv").toString(), "--commit", "--reason", "map"))
        FinanceCommands(db).importTransactions(who + listOf("--file", out.resolve("transactions.csv").toString(), "--commit", "--reason", "dues"))
        configure(hoa, "sim_hoa_x", suspended = false)
        configure(otherHoa, "sim_hoa_y", suspended = false)
    }

    @AfterEach
    fun close() = db.close()

    private fun configure(hoaId: UUID, merchant: String, suspended: Boolean) {
        ConfigurePayments(db, providers)(
            root, hoaId,
            ConfigurePayments.Change("simulated", merchant, mapOf(PaymentMethod.CARD to FeeRule(0, 500), PaymentMethod.ACH to FeeRule.NONE), suspended),
            commit = true, reason = "setup",
        ).shouldBeInstanceOf<ConfigurePayments.Result.Planned>().committed shouldBe true
    }

    private fun owner(unit: String): UserActor = owners.getOrPut(unit) {
        val email = "owner-$unit@example.test".lowercase()
        val identity = runBlocking { TestAuth.verifier.verify(TestAuth.token("uid-$unit", email)) }
        val accepted = AcceptInvitation(db)(identity, IssueInvitation(db)(hoa, properties.getValue(unit).propertyId, email).token)
            .shouldBeInstanceOf<AcceptInvitation.Result.Accepted>()
        UserActor(accepted.userId)
    }

    private fun initiate(unit: String) = InitiatePayment(db, providers)(
        owner(unit), properties.getValue(unit).propertyId,
        InitiatePayment.Request(PaymentMethod.CARD, 15_000, 500, 15_500, "key-${UUID.randomUUID()}"),
    )

    /** A $150 card payment ($155 charged) and its provider ID, moved to [status] by webhook if given. */
    private fun payment(unit: String = "A1", status: ProviderPaymentStatus? = ProviderPaymentStatus.PROCESSING): Pair<UUID, String> {
        val started = initiate(unit).shouldBeInstanceOf<InitiatePayment.Result.Started>()
        val providerId = SimulatedPaymentProvider.paymentId("sim_hoa_x", "seebrook-payment-${started.payment.id}")
        if (status != null) webhook(providerId, status) shouldBe HandlePaymentEvent.Outcome.APPLIED
        return started.payment.id to providerId
    }

    private fun webhook(providerId: String, status: ProviderPaymentStatus): HandlePaymentEvent.Outcome {
        val (payload, signature) = sim.event("sim_hoa_x", providerId, status, 15_500)
        return handle(SimulatedPaymentProvider.NAME, payload) { if (it == SimulatedPaymentProvider.SIGNATURE_HEADER) signature else null }
            .shouldBeInstanceOf<HandlePaymentEvent.Result.Accepted>().outcome
    }

    private fun status(paymentId: UUID) = db.dsl.select(PAYMENT.STATUS).from(PAYMENT).where(PAYMENT.ID.eq(paymentId)).fetchSingle().value1()

    private fun balance(unit: String): Long =
        db.dsl.select(LEDGER_ENTRY.AMOUNT_CENTS).from(LEDGER_ENTRY)
            .where(LEDGER_ENTRY.ACCOUNT_ID.eq(db.dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.PROPERTY_ID.eq(properties.getValue(unit).propertyId))))
            .fetch(LEDGER_ENTRY.AMOUNT_CENTS).sum()

    private fun availability(unit: String = "A1") =
        GetAccountSummary(db, providers = providers)(owner(unit), properties.getValue(unit).propertyId)
            .shouldBeInstanceOf<GetAccountSummary.Result.Summary>().summary.onlinePayments

    private fun audited(action: String) = db.dsl.fetchCount(EVENT, EVENT.ACTION.eq(action))

    @Test
    fun `a payment whose webhook was lost settles once from the provider's record`() {
        val opening = balance("A1")
        val (lost, lostId) = payment()
        val (fresh, _) = payment("A2")

        // Too recent to be checked: webhooks normally arrive within seconds.
        MonitorPayments(db, providers)().checked shouldBe 0

        provider.states[lostId] = ProviderPaymentSnapshot(lostId, ProviderPaymentStatus.SUCCEEDED, 15_500, "USD", null)
        val pass = MonitorPayments(db, providers, later(1))()
        pass.checked shouldBe 2
        pass.applied shouldBe 1
        status(lost) shouldBe "succeeded"
        status(fresh) shouldBe "processing" // the provider still says processing
        balance("A1") shouldBe opening - 15_000
        db.dsl.fetchCount(OUTBOX_JOB, OUTBOX_JOB.KIND.eq(PaymentEmailJob.KIND)) shouldBe 1 // the receipt

        // Checked at most hourly; settled payments are never asked about again.
        MonitorPayments(db, providers, later(1))().checked shouldBe 0
        MonitorPayments(db, providers, later(3))().checked shouldBe 1

        // The late webhook changes nothing: the dues are credited once.
        webhook(lostId, ProviderPaymentStatus.SUCCEEDED) shouldBe HandlePaymentEvent.Outcome.UNCHANGED
        balance("A1") shouldBe opening - 15_000
        db.dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.KIND.eq("payment")) shouldBe 1
    }

    @Test
    fun `failed payments the owner retried, and lookups that do not fit, are handled safely`() {
        val (retried, retriedId) = payment("A1", ProviderPaymentStatus.FAILED)
        val (odd, oddId) = payment("A2")
        provider.states[retriedId] = ProviderPaymentSnapshot(retriedId, ProviderPaymentStatus.SUCCEEDED, 15_500, "USD", null)
        provider.states[oddId] = ProviderPaymentSnapshot(oddId, ProviderPaymentStatus.SUCCEEDED, 99_999, "USD", null)

        val pass = MonitorPayments(db, providers, later(1))()
        pass.applied shouldBe 1
        pass.mismatched shouldBe 1
        status(retried) shouldBe "succeeded"
        status(odd) shouldBe "processing" // never applied (GA-18); kept for investigation
        db.dsl.select(PAYMENT_EVENT.EVENT_TYPE, PAYMENT_EVENT.OUTCOME, PAYMENT_EVENT.DETAIL).from(PAYMENT_EVENT)
            .where(PAYMENT_EVENT.PAYMENT_ID.eq(odd)).and(PAYMENT_EVENT.EVENT_TYPE.like("reconcile.%"))
            .fetch { listOf(it.value1(), it.value2(), it.value3()) } shouldContainExactly listOf(listOf("reconcile.succeeded", "mismatch", "amount or currency differs"))

        // Asked again later: recorded once, not again on every pass.
        MonitorPayments(db, providers, later(3))().mismatched shouldBe 0
        status(odd) shouldBe "processing"
    }

    @Test
    fun `an unreachable provider pauses that HOA's payments until it answers again`() {
        availability() shouldBe OnlinePayments.AVAILABLE
        provider.down = true
        repeat(3) { initiate("A1") shouldBe InitiatePayment.Result.ProviderUnavailable }

        // Paused: no payment actions, history still readable; the other HOA is unaffected.
        availability() shouldBe OnlinePayments.UNAVAILABLE
        QuotePayment(db, providers)(owner("A1"), properties.getValue("A1").propertyId, 15_000) shouldBe QuotePayment.Result.Unavailable
        initiate("A2") shouldBe InitiatePayment.Result.Unavailable
        GetPaymentSettings(db)(root, hoa)?.outageSince.shouldNotBeNull()
        GetPaymentSettings(db)(root, otherHoa)?.outageSince.shouldBeNull()
        audited("finance.payments_outage_started") shouldBe 1
        PaymentCommands(db, providers).settings(listOf("--operator", "root@example.test", "--hoa", "hoa-x")) shouldContain "PAUSED: provider unreachable"

        // Still down: stays paused, and nothing else is asked for that HOA.
        val lookups = provider.lookups
        MonitorPayments(db, providers, later(1))().paused shouldBe 1
        provider.lookups shouldBe lookups

        provider.down = false
        MonitorPayments(db, providers, later(1))().resumed shouldBe 1
        availability() shouldBe OnlinePayments.AVAILABLE
        audited("finance.payments_outage_ended") shouldBe 1
        payment("A1") // paying works again
    }

    @Test
    fun `lookups that fail pause payments too, and an administrator's hold is never lifted automatically`() {
        repeat(3) { payment(units[it]) }
        provider.down = true
        val pass = MonitorPayments(db, providers, later(1))()
        pass.outagesStarted shouldBe 1
        pass.providerErrors shouldBe 3
        availability() shouldBe OnlinePayments.UNAVAILABLE

        configure(hoa, "sim_hoa_x", suspended = true) // the administrator also holds payments
        provider.down = false
        MonitorPayments(db, providers, later(1))().resumed shouldBe 1
        GetPaymentSettings(db)(root, hoa)?.outageSince.shouldBeNull()
        availability() shouldBe OnlinePayments.UNAVAILABLE // the hold remains

        configure(hoa, "sim_hoa_x", suspended = false)
        availability() shouldBe OnlinePayments.AVAILABLE
    }

    @Test
    fun `a single failure does not pause payments`() {
        provider.down = true
        initiate("A1") shouldBe InitiatePayment.Result.ProviderUnavailable
        initiate("A1") shouldBe InitiatePayment.Result.ProviderUnavailable
        provider.down = false
        payment("A1") // answered: the count starts again
        provider.down = true
        initiate("A1") shouldBe InitiatePayment.Result.ProviderUnavailable
        availability() shouldBe OnlinePayments.AVAILABLE
    }
}
