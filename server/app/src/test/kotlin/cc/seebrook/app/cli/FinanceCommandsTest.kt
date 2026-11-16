package cc.seebrook.app.cli

import cc.seebrook.app.devtools.DevSeed
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.platform.config.Environment
import cc.seebrook.platform.db.Database
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File

class FinanceCommandsTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val finance = FinanceCommands(db)
    private val who = listOf("--operator", "root@example.test", "--hoa", DevSeed.HOA_SLUG)

    init {
        DevSeed.run(db, Environment.TEST)
        BootstrapRoot(db)("root@example.test")
    }

    @AfterEach
    fun close() = db.close()

    private fun fixture(name: String): String = File.createTempFile("finance", ".csv").apply {
        writeBytes(requireNotNull(FinanceCommandsTest::class.java.getResourceAsStream("/dev-fixtures/channel-harbor/$name")).readBytes())
        deleteOnExit()
    }.path

    private fun temp(text: String): String = File.createTempFile("finance", ".csv").apply { writeText(text); deleteOnExit() }.path

    @Test
    fun `finance commands map accounts, import, list, show a statement and reconcile`() {
        finance.importAccounts(who + listOf("--file", fixture("accounts.csv"))) shouldStartWith "Dry run"
        shouldThrow<CommandException> { finance.importAccounts(who + listOf("--file", fixture("accounts.csv"), "--commit")) }.exitCode shouldBe 2
        finance.importAccounts(who + listOf("--file", fixture("accounts.csv"), "--commit", "--reason", "Initial")) shouldContain "New accounts: 32"

        val transactions = fixture("transactions.csv")
        finance.importTransactions(who + listOf("--file", transactions)) shouldContain "Net change to balances: $1,725.00 across 32 account(s)"
        finance.importTransactions(who + listOf("--file", transactions, "--commit", "--reason", "2026 ledger")) shouldContain "New transactions: 261"
        finance.importTransactions(who + listOf("--file", transactions, "--commit", "--reason", "again")) shouldContain "already imported"

        finance.listBalances(who) shouldContain "Unit 05     CH-1005          $950.00  8 transactions"
        finance.listBalances(who) shouldContain "Total owed across accounts: $1,725.00"
        val statement = finance.accountStatement(who + listOf("--unit", "Unit 12"))
        statement shouldContain "Advance payment Q1 2027"
        statement shouldContain "Balance: -$450.00"
        finance.reconcileBalances(who + listOf("--file", fixture("expected-balances.csv"))) shouldBe "Reconciled: all 32 balances match."

        val wrong = shouldThrow<CommandException> { finance.reconcileBalances(who + listOf("--file", temp("account,balance\nCH-1005,1.00\n"))) }
        wrong.message shouldContain "NOT reconciled"
        wrong.message shouldContain "CH-1005 (Unit 05): spreadsheet $1.00, ledger $950.00"
        wrong.exitCode shouldBe 1

        val rejected = shouldThrow<CommandException> {
            finance.importTransactions(who + listOf("--file", temp("transaction_id,account,date,type,description,amount\nX,CH-1001,2026-13-01,assessment,D,1.00\n")))
        }
        rejected.message shouldContain "Line 2 [date]: '2026-13-01' is not a date"
        shouldThrow<CommandException> { finance.accountStatement(who + listOf("--unit", "Unit 99")) }.message shouldContain "No unit"
    }
}
