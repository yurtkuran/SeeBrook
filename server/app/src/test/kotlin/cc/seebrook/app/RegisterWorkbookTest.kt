package cc.seebrook.app

import cc.seebrook.app.cli.FinanceCommands
import cc.seebrook.app.cli.RegisterCommands
import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.app.support.TestWorkbooks
import cc.seebrook.app.support.TestWorkbooks.Entry
import cc.seebrook.finance.RegisterWorkbook
import cc.seebrook.identity.BootstrapRoot
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.Month

/**
 * ED-42: the HOA register workbook converts into the canonical import files, checked unit by unit
 * against the sheet's own balances, and imports idempotently with corrections and voids.
 */
class RegisterWorkbookTest {
    private val units = listOf("A1", "A2", "B1")
    private fun d(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)

    private val register = listOf(
        Entry("2026-00001", "A1", d(1, 3), 450.0),
        Entry("2026-00002", "V1001", d(1, 4), -200.0, "Landscaping", "Check"),
        Entry("2026-00003", "A2", d(2, 1), -25.0, "Late Fee", "Late Fee"),
        Entry("2026-00004", "A2", d(2, 10), 25.0, "Late Fee", "Deposit"),
        Entry("2026-00005", "B1", d(3, 1), 25.0, "Late Fee", "Late Fee"),
        Entry("2026-00006", "B1", d(3, 2), 0.0),
        Entry("2026-00007", "A2", d(3, 2, year = 1967), 150.0),
        Entry(null, "V2002", d(3, 5), -50.0, "Insurance", "ACH"),
        Entry("2026-00008", "A2", d(3, 6), 300.0),
    )
    private val spec = TestWorkbooks.Spec(units = units, previous = mapOf("A1" to 100.0, "B1" to -75.5), register = register)

    private fun convert(s: TestWorkbooks.Spec = spec) = RegisterWorkbook.convert(TestWorkbooks.build(s), 2026)
    private fun errors(s: TestWorkbooks.Spec) = convert(s).shouldBeInstanceOf<RegisterWorkbook.Result.Invalid>().errors

    @Test
    fun `the register converts to canonical files that match the sheet's balances`() {
        val output = convert().shouldBeInstanceOf<RegisterWorkbook.Result.Converted>().output
        val transactions = output.transactionsCsv.decodeToString()
        transactions.lines().first() shouldBe "transaction_id,account,date,type,description,amount,due_date,payment_ref"
        listOf(
            "OPEN-2026-A1,A1,2025-12-31,adjustment,Balance carried forward from 2025,-100.00,",
            "OPEN-2026-B1,B1,2025-12-31,adjustment,Balance carried forward from 2025,75.50,",
            "OPEN-2026-A2,A2,2025-12-31,adjustment,Balance carried forward from 2025,0.00,",
            "DUES-2026-02-B1,B1,2026-02-01,assessment,February 2026 HOA dues,150.00,2026-02-01",
            "2026-00001,A1,2026-01-03,payment,Payment: HOA Dues,-450.00,",
            "2026-00003,A2,2026-02-01,late_fee,Late fee,25.00,",
            "2026-00004,A2,2026-02-10,payment,Payment: Late Fee,-25.00,",
            "2026-00005,B1,2026-03-01,waiver,Late fee waived,-25.00,",
            "2026-00006,B1,2026-03-02,payment,Payment,0.00,",
        ).forEach { transactions shouldContain it }
        // Only unit rows of the year; never names, comments, vendors or dues not yet due.
        listOf("2026-00002", "2026-00007", "V2002", "Owner", "private note", "DUES-2026-04").forEach { transactions shouldNotContain it }
        transactions.lines().count { it.isNotEmpty() } shouldBe 1 + 3 + 9 + 6

        output.expectedBalancesCsv.decodeToString() shouldBe "account,balance\nA1,-100.00\nA2,150.00\nB1,500.50\n"
        output.accountsCsv.decodeToString() shouldBe "unit_label,account\nA1,A1\nA2,A2\nB1,B1\n"

        with(output.summary) {
            this.units shouldBe 3
            duesMonths shouldContainExactly listOf(Month.JANUARY, Month.FEBRUARY, Month.MARCH)
            duesCharges shouldBe 9
            openingBalances shouldBe 2
            payments shouldBe 3
            lateFees shouldBe 1
            waivers shouldBe 1
            adjustments shouldBe 0
            zeroRows shouldBe 1
            outsideYear shouldContainExactly listOf("2026-00007")
            otherRows shouldBe 2
        }
        // The same workbook always gives the same bytes, so a re-import is recognized.
        convert().shouldBeInstanceOf<RegisterWorkbook.Result.Converted>().output.transactionsCsv.decodeToString() shouldBe transactions
    }

    @Test
    fun `anything unexpected rejects the conversion with every problem listed`() {
        errors(spec.copy(currentOverride = mapOf("B1" to -400.0))).single() shouldContain
            "Unit B1: the converted transactions total $500.50, but the sheet's 2026 Balance means $400.00"
        val problems = errors(
            spec.copy(
                register = register + Entry(null, "A1", d(1, 5), 10.0) + Entry("2026-00009", "A1", d(1, 6), 1.005),
                currentOverride = mapOf("A1" to 100.0), // the synthetic sheet would otherwise carry the bad amount too
            ),
        )
        problems[0] shouldContain "row 14: unit A1 row has no transactionId"
        problems[1] shouldContain "row 15 (2026-00009): Amount is not an amount"
        errors(spec.copy(includeOwnersName = false)).single() shouldContain "No defined name 'owners'"
        errors(spec.copy(year = 2025)).single() shouldContain "No sheet '2026 income'"
        RegisterWorkbook.convert("not a workbook".toByteArray(), 2026).shouldBeInstanceOf<RegisterWorkbook.Result.Invalid>()
            .errors.single() shouldContain "Not a readable .xlsx workbook"
    }

    @Test
    fun `converted files import, reconcile, re-import as nothing and carry corrections and voids`() {
        val db = TestPostgres.freshDatabase()
        try {
            val hoa = Fixtures.hoa(db, "hoa-x")
            units.forEach { Fixtures.property(db, hoa, it) }
            BootstrapRoot(db)("root@example.test")
            val finance = FinanceCommands(db)
            val who = listOf("--operator", "root@example.test", "--hoa", "hoa-x")
            val dir = Files.createTempDirectory("register")

            fun convertTo(s: TestWorkbooks.Spec, name: String): Path {
                val workbook = dir.resolve("$name.xlsx").also { Files.write(it, TestWorkbooks.build(s)) }
                val out = dir.resolve(name)
                RegisterCommands.convert(listOf("--file", workbook.toString(), "--year", "2026", "--out", out.toString())) shouldContain
                    "Converted 2026: 3 units; every unit's balance matches the sheet's 2026 Balance."
                return out
            }
            fun file(dir: Path, name: String) = dir.resolve(name).toString()

            val march = convertTo(spec, "march")
            finance.importAccounts(who + listOf("--file", file(march, "accounts.csv"), "--commit", "--reason", "Map units")) shouldContain "New accounts: 3"
            finance.importTransactions(who + listOf("--file", file(march, "transactions.csv"), "--commit", "--reason", "March register")).let {
                it shouldContain "New transactions: 16"
                it shouldContain "New rows with amount 0, skipped: 2" // A2's zero opening balance and the zero register row
            }
            finance.reconcileBalances(who + listOf("--file", file(march, "expected-balances.csv"))) shouldBe "Reconciled: all 3 balances match."
            // Converting the same workbook again gives the same file: already imported.
            finance.importTransactions(who + listOf("--file", file(convertTo(spec, "march-again"), "transactions.csv"), "--commit", "--reason", "again")) shouldContain
                "already imported"

            // April: one payment corrected, the late fee voided, a new payment and April's dues.
            val april = spec.copy(
                monthsDue = 4,
                register = register.map {
                    when (it.id) {
                        "2026-00008" -> it.copy(amount = 250.0)
                        "2026-00003" -> it.copy(amount = 0.0)
                        else -> it
                    }
                } + Entry("2026-00010", "A1", d(4, 2), 150.0),
            )
            val aprilDir = convertTo(april, "april")
            finance.importTransactions(who + listOf("--file", file(aprilDir, "transactions.csv"), "--commit", "--reason", "April register")).let {
                it shouldContain "New transactions: 4"
                it shouldContain "Corrections: 2"
                it shouldContain "2026-00008: amount (-$300.00 -> -$250.00)"
                it shouldContain "Voids (amount set to 0): 1"
            }
            finance.reconcileBalances(who + listOf("--file", file(aprilDir, "expected-balances.csv"))) shouldBe "Reconciled: all 3 balances match."
            finance.accountStatement(who + listOf("--unit", "A2")) shouldContain "Late fee  (voided)"
        } finally {
            db.close()
        }
    }
}
