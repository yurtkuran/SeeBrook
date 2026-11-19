package cc.seebrook.app

import cc.seebrook.app.cli.CommandException
import cc.seebrook.app.cli.FinanceCommands
import cc.seebrook.app.cli.PaymentCommands
import cc.seebrook.app.cli.RegisterCommands
import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.app.support.TestWorkbooks
import cc.seebrook.app.support.TestWorkbooks.Entry
import cc.seebrook.db.generated.finance.Tables.LEDGER_ENTRY
import cc.seebrook.db.generated.finance.Tables.PAYMENT
import cc.seebrook.db.generated.finance.Tables.PAYMENT_ADJUSTMENT
import cc.seebrook.finance.ConfigurePayments
import cc.seebrook.finance.FeeRule
import cc.seebrook.finance.HandlePaymentEvent
import cc.seebrook.finance.InitiatePayment
import cc.seebrook.finance.ListAccountTransactions
import cc.seebrook.finance.PaymentEmailJob
import cc.seebrook.finance.SendPaymentEmail
import cc.seebrook.identity.AcceptInvitation
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.identity.UserContacts
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.email.BulkResult
import cc.seebrook.platform.email.EmailGateway
import cc.seebrook.platform.email.EmailTemplate
import cc.seebrook.platform.email.Recipient
import cc.seebrook.platform.email.RenderedEmail
import cc.seebrook.platform.jobs.JobRunner
import cc.seebrook.platform.payments.AdjustmentKind
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.ProviderPaymentStatus
import cc.seebrook.platform.payments.SimulatedPaymentProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * ED-45: refunds, failed refunds and disputes (ACH returns included) never change the original
 * payment; each is a linked compensating ledger entry for its dues part, applied once. Owners get
 * receipts and notices by email, and the register export and import carry them too.
 */
class RefundsTest {
    private val db = TestPostgres.freshDatabase()
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val provider = SimulatedPaymentProvider("refund-test-secret")
    private val providers = PaymentProviders(listOf(provider))
    private val handle = HandlePaymentEvent(db, providers)
    private val hoa = Fixtures.hoa(db, "hoa-x")
    private val units = listOf("A1", "A2", "B1")
    private val properties = units.associateWith { Fixtures.property(db, hoa, it) }
    private val who = listOf("--operator", "root@example.test", "--hoa", "hoa-x")
    private val finance = FinanceCommands(db)
    private val dir = Files.createTempDirectory("refunds")
    private val today = LocalDate.now(ZoneId.of("America/New_York"))
    private val spec = TestWorkbooks.Spec(units = units)
    private val owners = mutableMapOf<String, UserActor>()

    init {
        val out = convert(spec, "base")
        finance.importAccounts(who + listOf("--file", out.resolve("accounts.csv").toString(), "--commit", "--reason", "map"))
        finance.importTransactions(who + listOf("--file", out.resolve("transactions.csv").toString(), "--commit", "--reason", "dues"))
        ConfigurePayments(db, providers)(
            root, hoa,
            ConfigurePayments.Change("simulated", "sim_hoa_x", mapOf(PaymentMethod.CARD to FeeRule(0, 500), PaymentMethod.ACH to FeeRule.NONE), false),
            commit = true, reason = "setup",
        )
    }

    @AfterEach
    fun close() = db.close()

    private fun convert(s: TestWorkbooks.Spec, name: String): Path {
        val workbook = dir.resolve("$name.xlsx").also { Files.write(it, TestWorkbooks.build(s)) }
        val out = dir.resolve(name)
        RegisterCommands.convert(listOf("--file", workbook.toString(), "--year", "2026", "--out", out.toString()))
        return out
    }

    private fun owner(unit: String): UserActor = owners.getOrPut(unit) {
        val email = "owner-$unit@example.test".lowercase()
        val identity = runBlocking { TestAuth.verifier.verify(TestAuth.token("uid-$unit", email)) }
        val accepted = AcceptInvitation(db)(identity, IssueInvitation(db)(hoa, properties.getValue(unit).propertyId, email).token)
            .shouldBeInstanceOf<AcceptInvitation.Result.Accepted>()
        UserActor(accepted.userId)
    }

    private data class Paid(val reference: String, val providerId: String, val total: Long)

    /** A $150 card payment ($5 fee, $155 charged) that the provider confirms. */
    private fun cardPayment(unit: String = "A1", status: ProviderPaymentStatus = ProviderPaymentStatus.SUCCEEDED): Paid {
        val started = InitiatePayment(db, providers)(
            owner(unit), properties.getValue(unit).propertyId,
            InitiatePayment.Request(PaymentMethod.CARD, 15_000, 500, 15_500, "key-${UUID.randomUUID()}"),
        ).shouldBeInstanceOf<InitiatePayment.Result.Started>()
        val providerId = SimulatedPaymentProvider.paymentId("sim_hoa_x", "seebrook-payment-${started.payment.id}")
        send(provider.event("sim_hoa_x", providerId, status, 15_500))
        return Paid(started.payment.reference, providerId, 15_500)
    }

    private fun send(event: Pair<ByteArray, String>): HandlePaymentEvent.Outcome =
        handle(SimulatedPaymentProvider.NAME, event.first) { if (it == SimulatedPaymentProvider.SIGNATURE_HEADER) event.second else null }
            .shouldBeInstanceOf<HandlePaymentEvent.Result.Accepted>().outcome

    private fun adjust(p: Paid, kind: AdjustmentKind, ref: String, cents: Long, eventId: String = "evt-${UUID.randomUUID()}") =
        send(provider.adjustment("sim_hoa_x", p.providerId, kind, ref, cents, eventId))

    private fun balance(unit: String): Long =
        db.dsl.select(LEDGER_ENTRY.AMOUNT_CENTS).from(LEDGER_ENTRY)
            .where(LEDGER_ENTRY.ACCOUNT_ID.eq(db.dsl.select(cc.seebrook.db.generated.finance.Tables.ACCOUNT.ID).from(cc.seebrook.db.generated.finance.Tables.ACCOUNT)
                .where(cc.seebrook.db.generated.finance.Tables.ACCOUNT.PROPERTY_ID.eq(properties.getValue(unit).propertyId))))
            .fetch(LEDGER_ENTRY.AMOUNT_CENTS).sum()

    private fun adjustments(): List<Pair<String, Long>> =
        db.dsl.select(PAYMENT_ADJUSTMENT.REFERENCE, PAYMENT_ADJUSTMENT.DUES_CENTS).from(PAYMENT_ADJUSTMENT)
            .orderBy(PAYMENT_ADJUSTMENT.CREATED_AT, PAYMENT_ADJUSTMENT.ID).fetch { it.value1() to it.value2() }

    /** The ledger invariant, through reconcile-balances against the ledger's own balances. */
    private fun ledgerConsistent() {
        val expected = units.joinToString("\n", prefix = "account,balance\n") { u ->
            val b = balance(u)
            "$u,${if (b < 0) "-" else ""}${kotlin.math.abs(b) / 100}.${(kotlin.math.abs(b) % 100).toString().padStart(2, '0')}"
        }
        val file = dir.resolve("ledger-${UUID.randomUUID()}.csv").also { Files.writeString(it, expected) }
        finance.reconcileBalances(who + listOf("--file", file.toString())) shouldBe "Reconciled: all 3 balances match."
    }

    @Test
    fun `refunds reverse the dues first, once each, and a failed refund restores them`() {
        val opening = balance("A1") // 3 months of $150 dues
        val p = cardPayment()
        balance("A1") shouldBe opening - 15_000

        // A partial refund of $50, then the rest ($105: $100 of dues and the $5 fee).
        adjust(p, AdjustmentKind.REFUND, "re_1", 5_000) shouldBe HandlePaymentEvent.Outcome.APPLIED
        adjust(p, AdjustmentKind.REFUND, "re_1", 5_000) shouldBe HandlePaymentEvent.Outcome.UNCHANGED // same refund, another event
        adjust(p, AdjustmentKind.REFUND, "re_2", 10_500) shouldBe HandlePaymentEvent.Outcome.APPLIED
        balance("A1") shouldBe opening
        adjustments() shouldContainExactly listOf("${p.reference}-R1" to 5_000L, "${p.reference}-R2" to 10_000L)

        // The second refund did not go through: the money stayed with the HOA, so its dues count as paid again.
        adjust(p, AdjustmentKind.REFUND_FAILED, "re_2", 10_500) shouldBe HandlePaymentEvent.Outcome.APPLIED
        balance("A1") shouldBe opening - 10_000
        adjustments().last() shouldBe ("${p.reference}-R2F" to -10_000L)

        // The original payment is untouched (GA-19).
        db.dsl.select(PAYMENT.STATUS, PAYMENT.DUES_CENTS).from(PAYMENT).fetchSingle().let {
            it.value1() shouldBe "succeeded"
            it.value2() shouldBe 15_000L
        }
        val history = (ListAccountTransactions(db)(owner("A1"), properties.getValue("A1").propertyId) as ListAccountTransactions.Result.Listed).page.transactions
        // Newest first; same day, so by reference: the payment, then R1, R2, R2F.
        history.take(4).map { it.reference to it.amountCents } shouldContainExactly listOf(
            "${p.reference}-R2F" to -10_000L, "${p.reference}-R2" to 10_000L, "${p.reference}-R1" to 5_000L, p.reference to -15_000L,
        )
        history.first { it.reference == "${p.reference}-R1" }.description shouldBe "Refund of online payment ${p.reference}"
        ledgerConsistent()
    }

    @Test
    fun `a returned or disputed payment takes the dues back, and a won dispute restores them`() {
        val opening = balance("A2")
        val p = cardPayment("A2")
        adjust(p, AdjustmentKind.DISPUTE_WITHDRAWN, "dp_1", 15_500) shouldBe HandlePaymentEvent.Outcome.APPLIED
        balance("A2") shouldBe opening
        adjust(p, AdjustmentKind.DISPUTE_REINSTATED, "dp_1", 15_500) shouldBe HandlePaymentEvent.Outcome.APPLIED
        balance("A2") shouldBe opening - 15_000
        adjustments() shouldContainExactly listOf("${p.reference}-D1" to 15_000L, "${p.reference}-D1W" to -15_000L)

        // Events that do not fit change nothing and are kept for investigation.
        adjust(p, AdjustmentKind.DISPUTE_REINSTATED, "dp_9", 15_500) shouldBe HandlePaymentEvent.Outcome.MISMATCH // nothing withdrawn
        adjust(p, AdjustmentKind.REFUND, "re_9", 99_999) shouldBe HandlePaymentEvent.Outcome.MISMATCH // more than the payment
        val pending = cardPayment("B1", ProviderPaymentStatus.PROCESSING)
        adjust(pending, AdjustmentKind.REFUND, "re_8", 1_000) shouldBe HandlePaymentEvent.Outcome.MISMATCH // not received
        send(provider.adjustment("sim_hoa_x", "sim_pay_unknown", AdjustmentKind.REFUND, "re_7", 1_000)) shouldBe HandlePaymentEvent.Outcome.UNMATCHED
        // A replayed event changes nothing.
        adjust(p, AdjustmentKind.DISPUTE_WITHDRAWN, "dp_2", 15_500, eventId = "evt-fixed") shouldBe HandlePaymentEvent.Outcome.APPLIED
        adjust(p, AdjustmentKind.DISPUTE_WITHDRAWN, "dp_2", 15_500, eventId = "evt-fixed") shouldBe HandlePaymentEvent.Outcome.DUPLICATE
        balance("A2") shouldBe opening
        ledgerConsistent()
    }

    @Test
    fun `owners get a receipt, failure and refund notices by email`() {
        val sent = mutableListOf<Pair<Recipient, RenderedEmail>>()
        val gateway = object : EmailGateway {
            override fun sendTransactional(to: Recipient, email: RenderedEmail) { sent += to to email }
            override fun sendBulk(hoaId: UUID, recipients: List<Recipient>, template: EmailTemplate) = BulkResult(0, recipients)
        }
        val contacts = UserContacts(db)
        val runner = JobRunner(db, mapOf(PaymentEmailJob.KIND to SendPaymentEmail(db, gateway) { contacts.find(it) }))

        val p = cardPayment()
        cardPayment("A2", ProviderPaymentStatus.FAILED)
        adjust(p, AdjustmentKind.REFUND, "re_1", 15_500)
        runner.drain() shouldBe 3

        val (to, receipt) = sent.first { it.second.subject.startsWith("Payment received") }
        to.email shouldBe "owner-a1@example.test"
        receipt.subject shouldBe "Payment received: $150.00 for A1"
        receipt.text.let {
            it shouldContain "HOA hoa-x received your payment for A1 on "
            it shouldContain "HOA dues:      $150.00"
            it shouldContain "Card fee:      $5.00"
            it shouldContain "Total charged: $155.00 (card)"
            it shouldContain "Your account was credited $150.00."
            it shouldContain "Reference: ${p.reference}."
        }
        sent.single { it.first.email == "owner-a2@example.test" }.second.let {
            it.subject shouldBe "Your payment for A2 did not go through"
            it.text shouldContain "Nothing was credited to your account."
        }
        sent.single { it.second.subject.startsWith("Refund issued") }.second.text.let {
            it shouldContain "refunded $155.00 of your payment ${p.reference} for A1"
            it shouldContain "Your account balance increased by $150.00."
            it shouldContain "Reference: ${p.reference}-R1."
        }
        sent.joinToString { it.second.text } shouldNotContain "sim_pay_" // no provider IDs or card details
    }

    @Test
    fun `refunds export as negative register rows and round-trip`() {
        val p = cardPayment()
        adjust(p, AdjustmentKind.REFUND, "re_1", 5_000)
        val file = dir.resolve("payments.csv")
        PaymentCommands(db, providers).export(
            who + listOf("--from", today.minusDays(1).toString(), "--to", today.plusDays(1).toString(), "--out", file.toString()),
        ) shouldContain "Exported 1 received payment(s) and 1 refund(s) or return(s)"
        val lines = Files.readAllLines(file).drop(1)
        lines[1] shouldContain ",-50.00,,,,A1,"
        lines[1] shouldContain "Refund of online payment ${p.reference}; $50.00 moved,${p.reference}-R1"

        val pasted = spec.copy(
            register = listOf(
                Entry("2026-00010", "A1", today, 150.0, seebrookRef = p.reference),
                Entry("2026-00011", "A1", today, -50.0, seebrookRef = "${p.reference}-R1"),
            ),
        )
        val out = convert(pasted, "pasted")
        finance.importTransactions(who + listOf("--file", out.resolve("transactions.csv").toString())) shouldContain
            "Online payments matched (already in Seebrook, not posted again): 2"
        finance.reconcileBalances(who + listOf("--file", out.resolve("expected-balances.csv").toString())) shouldBe "Reconciled: all 3 balances match."

        val wrong = convert(pasted.copy(register = pasted.register.take(1) + Entry("2026-00011", "A1", today, -40.0, seebrookRef = "${p.reference}-R1")), "wrong")
        runCatching { finance.importTransactions(who + listOf("--file", wrong.resolve("transactions.csv").toString())) }
            .exceptionOrNull().shouldBeInstanceOf<CommandException>().message shouldContain "${p.reference}-R1 was -$50.00, not -$40.00"
    }
}
