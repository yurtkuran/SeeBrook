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
import cc.seebrook.finance.ConfigurePayments
import cc.seebrook.finance.FeeRule
import cc.seebrook.finance.HandlePaymentEvent
import cc.seebrook.finance.InitiatePayment
import cc.seebrook.finance.RegisterWorkbook
import cc.seebrook.identity.AcceptInvitation
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.ProviderPaymentStatus
import cc.seebrook.platform.payments.SimulatedPaymentProvider
import io.kotest.assertions.throwables.shouldThrow
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
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * ED-44: received online payments export as register rows; once pasted into the workbook with
 * their `Seebrook Ref`, the converted file imports without counting them twice, and the sheet and
 * Seebrook reconcile.
 */
class PaymentExportTest {
    private val db = TestPostgres.freshDatabase()
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val provider = SimulatedPaymentProvider("export-test-secret")
    private val providers = PaymentProviders(listOf(provider))
    private val hoa = Fixtures.hoa(db, "hoa-x") // America/New_York
    private val units = listOf("A1", "A2", "B1")
    private val properties = units.associateWith { Fixtures.property(db, hoa, it) }
    private val who = listOf("--operator", "root@example.test", "--hoa", "hoa-x")
    private val dir = Files.createTempDirectory("export")
    private val finance = FinanceCommands(db)
    private val today = LocalDate.now(ZoneId.of("America/New_York"))
    private val spec = TestWorkbooks.Spec(
        units = units,
        register = listOf(Entry("2026-00001", "A1", LocalDate.of(2026, 1, 3), 450.0)),
    )

    @AfterEach
    fun close() = db.close()

    private fun convert(s: TestWorkbooks.Spec, name: String): Path {
        val workbook = dir.resolve("$name.xlsx").also { Files.write(it, TestWorkbooks.build(s)) }
        val out = dir.resolve(name)
        RegisterCommands.convert(listOf("--file", workbook.toString(), "--year", "2026", "--out", out.toString())) shouldContain "every unit's balance matches"
        return out
    }

    private fun import(out: Path, reason: String) =
        finance.importTransactions(who + listOf("--file", out.resolve("transactions.csv").toString(), "--commit", "--reason", reason))

    private fun reconcile(out: Path) = finance.reconcileBalances(who + listOf("--file", out.resolve("expected-balances.csv").toString()))

    /** An owner of [unit] pays [cents] of dues; [status] is what the provider then reports. */
    private fun pay(unit: String, cents: Long, method: PaymentMethod, status: ProviderPaymentStatus?): String {
        val email = "owner-${UUID.randomUUID()}@example.test"
        val identity = runBlocking { TestAuth.verifier.verify(TestAuth.token("uid-$email", email)) }
        val p = properties.getValue(unit)
        val accepted = AcceptInvitation(db)(identity, IssueInvitation(db)(hoa, p.propertyId, email).token)
            .shouldBeInstanceOf<AcceptInvitation.Result.Accepted>()
        val fee = if (method == PaymentMethod.CARD) 500L else 0L
        val started = InitiatePayment(db, providers)(
            UserActor(accepted.userId), p.propertyId,
            InitiatePayment.Request(method, cents, fee, cents + fee, "key-${UUID.randomUUID()}"),
        ).shouldBeInstanceOf<InitiatePayment.Result.Started>()
        if (status != null) {
            val providerId = SimulatedPaymentProvider.paymentId("sim_hoa_x", "seebrook-payment-${started.payment.id}")
            val (payload, signature) = provider.event("sim_hoa_x", providerId, status, cents + fee)
            HandlePaymentEvent(db, providers)(SimulatedPaymentProvider.NAME, payload) { if (it == SimulatedPaymentProvider.SIGNATURE_HEADER) signature else null }
                .shouldBeInstanceOf<HandlePaymentEvent.Result.Accepted>()
        }
        return started.payment.reference
    }

    @Test
    fun `received payments export as register rows and round-trip without counting twice`() {
        val march = convert(spec, "march")
        finance.importAccounts(who + listOf("--file", march.resolve("accounts.csv").toString(), "--commit", "--reason", "map"))
        import(march, "March")
        ConfigurePayments(db, providers)(
            root, hoa,
            ConfigurePayments.Change("simulated", "sim_hoa_x", mapOf(PaymentMethod.CARD to FeeRule(0, 500), PaymentMethod.ACH to FeeRule.NONE), false),
            commit = true, reason = "setup",
        )

        val card = pay("A2", 15_000, PaymentMethod.CARD, ProviderPaymentStatus.SUCCEEDED)
        val ach = pay("B1", 30_000, PaymentMethod.ACH, ProviderPaymentStatus.SUCCEEDED)
        val pending = pay("B1", 5_000, PaymentMethod.ACH, ProviderPaymentStatus.PROCESSING)
        pay("A1", 5_000, PaymentMethod.CARD, ProviderPaymentStatus.FAILED)

        // Seebrook now holds payments the sheet does not have yet.
        shouldThrow<CommandException> { reconcile(march) }.message shouldContain "A2 (A2): spreadsheet $450.00, ledger $300.00"

        val file = dir.resolve("payments.csv")
        val range = listOf("--from", today.minusDays(1).toString(), "--to", today.plusDays(1).toString())
        PaymentCommands(db, providers).export(who + range + listOf("--out", file.toString(), "--first-id", "2026-00100")).let {
            it shouldContain "Exported 2 received payment(s)"
            it shouldContain "Net to units: $450.00; fees paid by owners: $5.00"
        }
        val lines = Files.readAllLines(file)
        lines[0] shouldBe "transactionId,ID,Posted Date,Transaction Date,Transaction Type,Account,Check/Serial #,Amount,Posted Balance," +
            "Posted Balance,Balance,Payee ID,Payee,Invoice Number,Category,Topic,Year,Month,Comment,Seebrook Ref"
        val date = today.format(DateTimeFormatter.ofPattern("M/d/yyyy"))
        // Oldest first: the card payment was received before the bank payment.
        lines.drop(1) shouldBe listOf(
            "2026-00100,101,,$date,Online Payment,,,150.00,,,,A2,,,,,,,Online payment by card; fee $5.00; total $155.00,$card",
            "2026-00101,101,,$date,Online Payment,,,300.00,,,,B1,,,,,,,Online payment by bank account,$ach",
        )
        lines.joinToString() shouldNotContain pending
        shouldThrow<CommandException> { PaymentCommands(db, providers).export(who + range + listOf("--out", file.toString())) }.message shouldContain "already exists"

        // The bookkeeper pastes the rows; the next conversion carries the references, and the import
        // checks them against the payments instead of posting them again.
        val pasted = spec.copy(
            register = spec.register +
                Entry("2026-00100", "A2", today, 150.0, seebrookRef = card) +
                Entry("2026-00101", "B1", today, 300.0, seebrookRef = ach),
        )
        val april = convert(pasted, "april")
        Files.readString(april.resolve("transactions.csv")) shouldContain ",payment,Online payment,-150.00,,$card"
        finance.importTransactions(who + listOf("--file", april.resolve("transactions.csv").toString())).let {
            it shouldContain "Online payments matched (already in Seebrook, not posted again): 2"
            it shouldContain "Net change to balances: $0.00"
        }
        reconcile(april) shouldBe "Reconciled: all 3 balances match."

        // Anything that does not match the payment rejects the whole file.
        fun conflicts(vararg entries: Entry, register: List<Entry> = spec.register): String {
            val out = convert(spec.copy(register = register + entries), "bad-${UUID.randomUUID()}")
            return shouldThrow<CommandException> { import(out, "bad") }.message!!
        }
        conflicts(Entry("2026-00100", "A2", today, 140.0, seebrookRef = card)) shouldContain "online payment $card was $150.00, not $140.00"
        conflicts(Entry("2026-00100", "A1", today, 150.0, seebrookRef = card)) shouldContain "was made for another unit"
        conflicts(Entry("2026-00102", "B1", today, 50.0, seebrookRef = pending)) shouldContain "has not been received (processing)"
        conflicts(Entry("2026-00103", "B1", today, 50.0, seebrookRef = "PAY-ZZZZZZZZ")) shouldContain "no online payment PAY-ZZZZZZZZ"
        // A transaction already imported on its own cannot later be marked as an online payment.
        conflicts(Entry("2026-00001", "A2", LocalDate.of(2026, 1, 3), 150.0, seebrookRef = card), register = emptyList()) shouldContain "count twice"
    }

    @Test
    fun `the converter refuses malformed or repeated references`() {
        val bad = spec.copy(
            register = spec.register +
                Entry("2026-00100", "A2", today, 150.0, seebrookRef = "PAY-1") +
                Entry("2026-00101", "A2", today, -10.0, seebrookRef = "PAY-AAAAAAAA") +
                Entry("2026-00102", "B1", today, 10.0, seebrookRef = "PAY-BBBBBBBB") +
                Entry("2026-00103", "B1", today, 10.0, seebrookRef = "PAY-BBBBBBBB"),
        )
        val errors = RegisterWorkbook.convert(TestWorkbooks.build(bad), 2026).shouldBeInstanceOf<RegisterWorkbook.Result.Invalid>().errors
        errors.any { "'PAY-1' is not a Seebrook payment reference" in it } shouldBe true
        errors.any { "PAY-AAAAAAAA) must be money received" in it } shouldBe true
        errors.any { "PAY-BBBBBBBB also appears on row" in it } shouldBe true
    }
}
