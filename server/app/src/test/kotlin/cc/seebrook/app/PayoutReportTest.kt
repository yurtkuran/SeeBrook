package cc.seebrook.app

import cc.seebrook.app.cli.FinanceCommands
import cc.seebrook.app.cli.PaymentCommands
import cc.seebrook.app.cli.RegisterCommands
import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.app.support.TestWorkbooks
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.finance.ConfigurePayments
import cc.seebrook.finance.FeeRule
import cc.seebrook.finance.HandlePaymentEvent
import cc.seebrook.finance.InitiatePayment
import cc.seebrook.finance.PayoutReport
import cc.seebrook.identity.AcceptInvitation
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.payments.AdjustmentKind
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProvider
import cc.seebrook.platform.payments.PaymentProviderException
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.PayoutStatus
import cc.seebrook.platform.payments.ProviderPayout
import cc.seebrook.platform.payments.ProviderPaymentStatus
import cc.seebrook.platform.payments.SettlementKind
import cc.seebrook.platform.payments.SettlementLine
import cc.seebrook.platform.payments.SimulatedPaymentProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID

/**
 * ED-47: each bank deposit from the provider is broken down into payments, refunds, the
 * provider's actual fees and the net, matched to Seebrook's records; anything that does not
 * match is listed, and nothing is changed.
 */
class PayoutReportTest {
    /** The simulated provider, with payouts scripted by the test. */
    private class ScriptedProvider(private val sim: SimulatedPaymentProvider) : PaymentProvider by sim {
        val payouts = mutableListOf<Pair<ProviderPayout, List<SettlementLine>>>()
        var down = false

        override fun payouts(merchantRef: String, from: LocalDate, to: LocalDate): List<ProviderPayout> {
            if (down) throw PaymentProviderException("simulated outage")
            return payouts.map { it.first }.filter { !it.arrivalDate.isBefore(from) && !it.arrivalDate.isAfter(to) }
        }

        override fun payoutLines(merchantRef: String, payoutId: String) = payouts.single { it.first.payoutId == payoutId }.second
    }

    private val db = TestPostgres.freshDatabase()
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val sim = SimulatedPaymentProvider("payout-test-secret")
    private val provider = ScriptedProvider(sim)
    private val providers = PaymentProviders(listOf(provider))
    private val hoa = Fixtures.hoa(db, "hoa-x")
    private val units = listOf("A1", "A2", "B1")
    private val properties = units.associateWith { Fixtures.property(db, hoa, it) }
    private val owners = mutableMapOf<String, UserActor>()
    private val dir = Files.createTempDirectory("payouts")
    private val who = listOf("--operator", "root@example.test", "--hoa", "hoa-x")
    private val nov20: LocalDate = LocalDate.parse("2026-11-20")
    private val nov21: LocalDate = LocalDate.parse("2026-11-21")

    init {
        val workbook = dir.resolve("base.xlsx").also { Files.write(it, TestWorkbooks.build(TestWorkbooks.Spec(units = units))) }
        val out = dir.resolve("base")
        RegisterCommands.convert(listOf("--file", workbook.toString(), "--year", "2026", "--out", out.toString()))
        FinanceCommands(db).importAccounts(who + listOf("--file", out.resolve("accounts.csv").toString(), "--commit", "--reason", "map"))
        FinanceCommands(db).importTransactions(who + listOf("--file", out.resolve("transactions.csv").toString(), "--commit", "--reason", "dues"))
        ConfigurePayments(db, providers)(
            root, hoa,
            ConfigurePayments.Change("simulated", "sim_hoa_x", mapOf(PaymentMethod.CARD to FeeRule(0, 500), PaymentMethod.ACH to FeeRule.NONE), false),
            commit = true, reason = "setup",
        )
    }

    @AfterEach
    fun close() = db.close()

    private fun owner(unit: String): UserActor = owners.getOrPut(unit) {
        val email = "owner-$unit@example.test".lowercase()
        val identity = runBlocking { TestAuth.verifier.verify(TestAuth.token("uid-$unit", email)) }
        val accepted = AcceptInvitation(db)(identity, IssueInvitation(db)(hoa, properties.getValue(unit).propertyId, email).token)
            .shouldBeInstanceOf<AcceptInvitation.Result.Accepted>()
        UserActor(accepted.userId)
    }

    private fun send(event: Pair<ByteArray, String>) =
        HandlePaymentEvent(db, providers)(SimulatedPaymentProvider.NAME, event.first) { if (it == SimulatedPaymentProvider.SIGNATURE_HEADER) event.second else null }
            .shouldBeInstanceOf<HandlePaymentEvent.Result.Accepted>().outcome

    /** A $150 payment: by card ($5 owner fee, $155 charged) or by bank ($150). Returns (reference, provider ID). */
    private fun pay(unit: String, method: PaymentMethod, status: ProviderPaymentStatus = ProviderPaymentStatus.SUCCEEDED): Pair<String, String> {
        val fee = if (method == PaymentMethod.CARD) 500L else 0L
        val started = InitiatePayment(db, providers)(
            owner(unit), properties.getValue(unit).propertyId,
            InitiatePayment.Request(method, 15_000, fee, 15_000 + fee, "key-${UUID.randomUUID()}"),
        ).shouldBeInstanceOf<InitiatePayment.Result.Started>()
        val providerId = SimulatedPaymentProvider.paymentId("sim_hoa_x", "seebrook-payment-${started.payment.id}")
        send(sim.event("sim_hoa_x", providerId, status, 15_000 + fee)) shouldBe HandlePaymentEvent.Outcome.APPLIED
        return started.payment.reference to providerId
    }

    private fun line(kind: SettlementKind, source: String?, payment: String?, gross: Long, fee: Long) =
        SettlementLine(kind, source, payment, gross, fee, gross - fee, "USD")

    private fun report(from: LocalDate = nov20, to: LocalDate = nov21) =
        PayoutReport(db, providers)(root, hoa, from, to).shouldBeInstanceOf<PayoutReport.Result.Report>().payouts

    @Test
    fun `a deposit is broken down into payments, refunds and the provider's actual fees`() {
        val (card, cardId) = pay("A1", PaymentMethod.CARD)
        val (bank, bankId) = pay("A2", PaymentMethod.ACH)
        send(sim.adjustment("sim_hoa_x", cardId, AdjustmentKind.REFUND, "re_1", 5_000)) shouldBe HandlePaymentEvent.Outcome.APPLIED
        val lines = listOf(
            line(SettlementKind.PAYMENT, "ch_1", cardId, 15_500, 480),
            line(SettlementKind.PAYMENT, "py_2", bankId, 15_000, 75),
            line(SettlementKind.REFUND, "re_1", cardId, -5_000, 0),
            line(SettlementKind.FEE, "fee_1", null, -200, 0),
        )
        provider.payouts += ProviderPayout("po_1", 24_745, "USD", PayoutStatus.PAID, nov20) to lines

        val payout = report().single()
        payout.lines.map { Triple(it.reference, it.account, it.problem) } shouldContainExactly listOf(
            Triple(card, "A1", null), Triple(bank, "A2", null), Triple("$card-R1", "A1", null), Triple(null, null, null),
        )
        payout.paymentsCents shouldBe 30_500
        payout.refundsCents shouldBe -5_000
        payout.providerFeesCents shouldBe -755 // 4.80 + 0.75 on the payments, 2.00 billed separately
        payout.netCents shouldBe 24_745
        payout.duesCents shouldBe 30_000
        payout.ownerFeesCents shouldBe 500
        payout.processingCostCents shouldBe 255
        payout.problems shouldBe emptyList()

        val out = dir.resolve("payouts.csv")
        val text = PaymentCommands(db, providers).payoutReport(who + listOf("--from", "2026-11-20", "--to", "2026-11-21", "--out", out.toString()))
        text shouldContain "Payout po_1, arriving 2026-11-20 (paid): $247.45 to the bank"
        text shouldContain "Payments received (2)"
        text shouldContain "Processing cost to the HOA: $2.55 (provider fees less owners' fees)."
        text shouldContain "Every line matches Seebrook."
        text shouldContain "1 payout(s), $247.45 to the bank; provider fees $7.55; everything matches."
        Files.readAllLines(out) shouldContainExactly listOf(
            "payout,arrival_date,payout_status,type,reference,account,gross,provider_fee,net,dues,owner_fee,problem",
            "po_1,2026-11-20,paid,payment,$card,A1,155.00,-4.80,150.20,150.00,5.00,",
            "po_1,2026-11-20,paid,payment,$bank,A2,150.00,-0.75,149.25,150.00,0.00,",
            "po_1,2026-11-20,paid,refund,$card-R1,A1,-50.00,0.00,-50.00,,,",
            "po_1,2026-11-20,paid,fee,,,-2.00,0.00,-2.00,,,",
        )
        db.dsl.fetchCount(EVENT, EVENT.ACTION.eq("finance.payout_report")) shouldBe 2 // the report and the command, each audited
    }

    @Test
    fun `anything that does not match Seebrook is listed for investigation`() {
        val (pending, pendingId) = pay("B1", PaymentMethod.CARD, ProviderPaymentStatus.PROCESSING)
        val (_, paidId) = pay("A1", PaymentMethod.CARD)
        provider.payouts += ProviderPayout("po_2", 99_999, "USD", PayoutStatus.IN_TRANSIT, nov21) to listOf(
            line(SettlementKind.PAYMENT, "ch_3", pendingId, 15_500, 480), // still processing in Seebrook
            line(SettlementKind.PAYMENT, "ch_4", paidId, 15_000, 480), // a different amount
            line(SettlementKind.PAYMENT, "ch_5", "pi_unknown", 1_000, 59), // not a Seebrook payment
            line(SettlementKind.REFUND, "re_9", paidId, -1_000, 0), // a refund whose webhook never came
            line(SettlementKind.OTHER, null, null, 100, 0),
        )
        val payout = report().single()
        payout.problems shouldContainExactly listOf(
            "lines add up to $295.81, not the deposit of $999.99",
            "$pending $155.00: paid out, but processing in Seebrook (run reconcile-payments)",
            "${payout.lines[1].reference} $150.00: charged $150.00, Seebrook expected $155.00",
            "payment $10.00: not a payment Seebrook started",
            "refund -$10.00: refund not recorded in Seebrook (check the webhook)",
            "other $1.00: not a payment, refund, dispute or fee: check it in the provider's dashboard",
        )
        PaymentCommands(db, providers).payoutReport(who + listOf("--from", "2026-11-21", "--to", "2026-11-21")) shouldContain
            "CHECK: payment $10.00: not a payment Seebrook started"

        // Only payouts arriving in the range; only finance administrators; provider trouble is reported, not guessed.
        report(nov20, nov20) shouldBe emptyList()
        PayoutReport(db, providers)(owner("A1"), hoa, nov20, nov21) shouldBe PayoutReport.Result.Forbidden
        provider.down = true
        PayoutReport(db, providers)(root, hoa, nov20, nov21) shouldBe PayoutReport.Result.ProviderUnavailable
    }
}
