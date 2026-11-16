package cc.seebrook.app

import cc.seebrook.app.devtools.DevSeed
import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.db.generated.finance.Tables.ACCOUNT
import cc.seebrook.db.generated.finance.Tables.IMPORT_BATCH
import cc.seebrook.db.generated.finance.Tables.LEDGER_ENTRY
import cc.seebrook.db.generated.finance.Tables.TRANSACTION
import cc.seebrook.db.generated.finance.Tables.TRANSACTION_CORRECTION
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.finance.AccountStatement
import cc.seebrook.finance.ImportAccounts
import cc.seebrook.finance.ImportTransactions
import cc.seebrook.finance.ListBalances
import cc.seebrook.finance.ReconcileBalances
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.GrantRole
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.config.Environment
import cc.seebrook.platform.db.Database
import cc.seebrook.tenant.FindHoa
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * M4 part 1 (ED-41): account mapping, atomic and idempotent transaction import with audited
 * corrections, the append-only ledger and reconciliation of all 32 fixture balances.
 */
class FinanceImportTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val hoa: UUID = run {
        DevSeed.run(db, Environment.TEST)
        requireNotNull(FindHoa(db).bySlug(DevSeed.HOA_SLUG)).id
    }
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val accounts = ImportAccounts(db)
    private val transactions = ImportTransactions(db)

    @AfterEach
    fun close() = db.close()

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/dev-fixtures/channel-harbor/$name")) { "no fixture $name" }.readBytes()

    private fun csv(vararg rows: String) =
        ("transaction_id,account,date,type,description,amount,due_date\n" + rows.joinToString("\n") + "\n").toByteArray()

    private fun mapAccounts() =
        accounts(root, hoa, fixture("accounts.csv"), commit = true, reason = "Initial mapping").shouldBeInstanceOf<ImportAccounts.Result.Imported>()

    private fun import(content: ByteArray, reason: String = "Monthly import") = transactions(root, hoa, "file.csv", content, commit = true, reason = reason)

    private fun balance(unit: String) = (AccountStatement(db)(root, hoa, unit) as AccountStatement.Result.Statement).balanceCents
    private fun reconcile(content: ByteArray) =
        (ReconcileBalances(db)(root, hoa, "expected.csv", content) as ReconcileBalances.Result.Reconciled).report

    @Test
    fun `the 32-unit fixture imports and every balance reconciles with the spreadsheet`() {
        accounts(root, hoa, fixture("accounts.csv"), commit = false).shouldBeInstanceOf<ImportAccounts.Result.Planned>()
            .plan.toCreate.size shouldBe 32
        db.dsl.fetchCount(ACCOUNT) shouldBe 0
        mapAccounts().plan.toCreate.size shouldBe 32
        // Re-running the mapping changes nothing.
        accounts(root, hoa, fixture("accounts.csv"), commit = true, reason = "again")
            .shouldBeInstanceOf<ImportAccounts.Result.Imported>().plan.isEmpty shouldBe true

        val dryRun = transactions(root, hoa, "transactions.csv", fixture("transactions.csv"), commit = false)
            .shouldBeInstanceOf<ImportTransactions.Result.Planned>().plan
        dryRun.toCreate.size shouldBe 261
        dryRun.netChangeCents shouldBe 172_500
        db.dsl.fetchCount(TRANSACTION) shouldBe 0

        val imported = import(fixture("transactions.csv")).shouldBeInstanceOf<ImportTransactions.Result.Imported>()
        db.dsl.fetchCount(TRANSACTION) shouldBe 261
        db.dsl.fetchCount(LEDGER_ENTRY) shouldBe 261

        val report = reconcile(fixture("expected-balances.csv"))
        report.reconciled shouldBe true
        report.matched shouldBe 32
        balance("Unit 05") shouldBe 95_000
        balance("Unit 12") shouldBe -45_000 // a credit: paid ahead
        balance("Unit 01") shouldBe 0
        (ListBalances(db)(root, hoa) as ListBalances.Result.Listed).totalCents shouldBe 172_500

        // Idempotent retry: the same file again changes nothing.
        import(fixture("transactions.csv")).shouldBeInstanceOf<ImportTransactions.Result.AlreadyImported>().batchId shouldBe imported.batchId
        // A different file whose rows are all already imported changes nothing either.
        val subset = fixture("transactions.csv").decodeToString().lines().take(40).joinToString("\n").toByteArray()
        import(subset) shouldBe ImportTransactions.Result.NothingToImport
        db.dsl.fetchCount(IMPORT_BATCH) shouldBe 1
        db.dsl.fetchCount(LEDGER_ENTRY) shouldBe 261
        db.dsl.fetchCount(EVENT, EVENT.ACTION.eq("finance.transactions_imported")) shouldBe 1
    }

    @Test
    fun `a changed row becomes an audited correction with a reversal, never a rewrite`() {
        mapAccounts()
        import(csv("A-1,CH-1001,2026-01-01,assessment,Q1 dues,450.00,2026-01-15", "P-1,CH-1001,2026-01-10,payment,Check,-400.00,"))
        balance("Unit 01") shouldBe 5_000

        val plan = transactions(
            root, hoa, "fix.csv",
            csv("A-1,CH-1001,2026-01-01,assessment,Q1 2026 dues,450.00,2026-01-15", "P-1,CH-1001,2026-01-10,payment,Check,-450.00,", "A-2,CH-1001,2026-04-01,assessment,Q2 dues,450.00,"),
            commit = false,
        ).shouldBeInstanceOf<ImportTransactions.Result.Planned>().plan
        plan.toCreate.size shouldBe 1
        plan.toCorrect.map { it.row.externalId to it.changed } shouldContainExactly listOf("A-1" to listOf("description"), "P-1" to listOf("amount"))
        plan.netChangeCents shouldBe 40_000 // -50.00 on P-1, +450.00 for A-2

        import(
            csv("A-1,CH-1001,2026-01-01,assessment,Q1 2026 dues,450.00,2026-01-15", "P-1,CH-1001,2026-01-10,payment,Check,-450.00,", "A-2,CH-1001,2026-04-01,assessment,Q2 dues,450.00,"),
            reason = "Bookkeeper corrected the check amount",
        ).shouldBeInstanceOf<ImportTransactions.Result.Imported>()
        balance("Unit 01") shouldBe 45_000

        val p1 = db.dsl.selectFrom(TRANSACTION).where(TRANSACTION.EXTERNAL_ID.eq("P-1")).fetchSingle()
        p1.amountCents shouldBe -45_000
        p1.version shouldBe 2
        // The ledger keeps the original entry and adds a reversal and the corrected amount.
        db.dsl.select(LEDGER_ENTRY.KIND, LEDGER_ENTRY.AMOUNT_CENTS).from(LEDGER_ENTRY)
            .where(LEDGER_ENTRY.TRANSACTION_ID.eq(p1.id)).orderBy(LEDGER_ENTRY.ID)
            .fetch { it.value1() to it.value2() } shouldContainExactly listOf("original" to -40_000L, "reversal" to 40_000L, "correction" to -45_000L)
        // A description-only correction posts nothing to the ledger but is still recorded.
        val corrections = db.dsl.selectFrom(TRANSACTION_CORRECTION).fetch()
        corrections.size shouldBe 2
        corrections.single { it.transactionId == p1.id }.before.data() shouldContain "-40000"
        corrections.single { it.transactionId == p1.id }.after.data() shouldContain "-45000"
        reconcile("account,balance\nCH-1001,450.00\n".toByteArray()).inconsistentAccounts.shouldBeEmpty()
    }

    @Test
    fun `a file with any error or conflict is rejected as a whole`() {
        mapAccounts()
        import(csv("A-1,CH-1001,2026-01-01,assessment,Q1 dues,450.00,"))

        transactions(root, hoa, "bad.csv", csv("A-2,CH-1002,2026-01-01,assessment,Q1 dues,450.00,", "A-3,CH-1003,not-a-date,assessment,Q1,450.00,"), true, "x")
            .shouldBeInstanceOf<ImportTransactions.Result.Invalid>().errors.single().line shouldBe 3
        val conflicts = transactions(
            root, hoa, "conflict.csv",
            csv("A-2,CH-1002,2026-01-01,assessment,Q1 dues,450.00,", "A-1,CH-1002,2026-01-01,assessment,Q1 dues,450.00,", "A-9,XX-0001,2026-01-01,assessment,Q1,450.00,"),
            true, "x",
        ).shouldBeInstanceOf<ImportTransactions.Result.Conflicts>().conflicts
        conflicts.single { "XX-0001" in it } shouldContain "not mapped"
        conflicts.single { "'A-1'" in it } shouldContain "another account"

        // Nothing from either file was applied, not even the valid A-2 rows.
        db.dsl.fetchCount(TRANSACTION) shouldBe 1
        db.dsl.fetchCount(IMPORT_BATCH) shouldBe 1
        transactions(root, hoa, "x.csv", csv("A-2,CH-1002,2026-01-01,assessment,Q1 dues,450.00,"), true, " ") shouldBe
            ImportTransactions.Result.ReasonRequired
    }

    @Test
    fun `a failure while applying rolls everything back, and the retry then succeeds once`() {
        mapAccounts()
        // Make the database fail part-way through the commit, after earlier rows were written.
        db.dsl.execute(
            """
            CREATE FUNCTION finance.fail_once() RETURNS trigger LANGUAGE plpgsql AS
            ${'$'}${'$'} BEGIN IF NEW.external_id = 'A-150' THEN RAISE EXCEPTION 'simulated failure'; END IF; RETURN NEW; END ${'$'}${'$'};
            CREATE TRIGGER fail_once BEFORE INSERT ON finance.transaction FOR EACH ROW EXECUTE FUNCTION finance.fail_once();
            """.trimIndent(),
        )
        val rows = (1..200).map { "A-$it,CH-10${"%02d".format(it % 32 + 1)},2026-01-01,assessment,Dues,10.00," }.toTypedArray()

        shouldThrow<Exception> { import(csv(*rows)) }
        db.dsl.fetchCount(TRANSACTION) shouldBe 0
        db.dsl.fetchCount(LEDGER_ENTRY) shouldBe 0
        db.dsl.fetchCount(IMPORT_BATCH) shouldBe 0
        db.dsl.fetchCount(EVENT, EVENT.ACTION.eq("finance.transactions_imported")) shouldBe 0

        db.dsl.execute("DROP TRIGGER fail_once ON finance.transaction")
        import(csv(*rows)).shouldBeInstanceOf<ImportTransactions.Result.Imported>()
        import(csv(*rows)).shouldBeInstanceOf<ImportTransactions.Result.AlreadyImported>()
        db.dsl.fetchCount(TRANSACTION) shouldBe 200
        db.dsl.fetchCount(LEDGER_ENTRY) shouldBe 200
    }

    @Test
    fun `the ledger, corrections and batches cannot be changed or deleted, nor transactions deleted`() {
        mapAccounts()
        import(csv("A-1,CH-1001,2026-01-01,assessment,Q1 dues,450.00,"))
        listOf(
            "UPDATE finance.ledger_entry SET amount_cents = 1",
            "DELETE FROM finance.ledger_entry",
            "TRUNCATE finance.ledger_entry CASCADE",
            "UPDATE finance.import_batch SET row_count = 0",
            "DELETE FROM finance.transaction",
        ).forEach { sql -> shouldThrow<Exception> { db.dsl.execute(sql) }.message shouldContain "append-only" }
        db.dsl.fetchCount(LEDGER_ENTRY) shouldBe 1
    }

    @Test
    fun `account mapping is exact and fixed once transactions exist`() {
        accounts(root, hoa, "unit_label,account\nUnit 01,CH-1001\nUnit 02,CH-1002\n".toByteArray(), true, "r")
        // Corrected before any transaction: allowed.
        accounts(root, hoa, "unit_label,account\nUnit 02,CH-2002\n".toByteArray(), true, "typo")
            .shouldBeInstanceOf<ImportAccounts.Result.Imported>().plan.toRemap.single().to shouldBe "CH-2002"
        import(csv("A-1,CH-1001,2026-01-01,assessment,Q1 dues,450.00,"))

        val conflicts = accounts(
            root, hoa, "unit_label,account\nUnit 01,CH-9001\nUnit 03,CH-2002\nUnit 99,CH-9999\n".toByteArray(), true, "r",
        ).shouldBeInstanceOf<ImportAccounts.Result.Conflicts>().conflicts
        conflicts[0] shouldContain "has transactions"
        conflicts[1] shouldContain "already belongs to another unit"
        conflicts[2] shouldContain "no unit 'Unit 99'"
        accounts(root, hoa, "unit_label,account\nUnit 03,CH-1003\nUnit 03,CH-1004\n".toByteArray(), true, "r")
            .shouldBeInstanceOf<ImportAccounts.Result.Invalid>().errors.single().message shouldContain "repeats line 2"
        db.dsl.fetchCount(ACCOUNT) shouldBe 2
    }

    @Test
    fun `reconciliation reports every difference`() {
        mapAccounts()
        import(fixture("transactions.csv"))
        val expected = fixture("expected-balances.csv").decodeToString()
            .replace("CH-1005,950.00", "CH-1005,925.00")
            .replace("CH-1032,0.00\n", "")
            .plus("CH-7777,0.00\n")
        val report = reconcile(expected.toByteArray())
        report.reconciled shouldBe false
        report.matched shouldBe 30
        report.mismatches.single().let {
            it.account shouldBe "CH-1005"
            it.unitLabel shouldBe "Unit 05"
            it.expectedCents shouldBe 92_500
            it.actualCents shouldBe 95_000
        }
        report.unknownAccounts shouldBe listOf("CH-7777")
        report.missingAccounts shouldBe listOf("CH-1032")
        db.dsl.selectFrom(EVENT).where(EVENT.ACTION.eq("finance.balances_reconciled")).fetchSingle().details.data() shouldContain "\"reconciled\": false"
    }

    @Test
    fun `only financial administrators of the HOA may import or read finances`() {
        val otherHoa = Fixtures.hoa(db, "hoa-b")
        Fixtures.property(db, otherHoa, "Unit 01")
        listOf("membership@example.test", "docs@example.test", "finance-b@example.test", "finance@example.test").forEach {
            db.dsl.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, it).execute()
        }
        GrantRole(db)(root, "membership@example.test", Role.MEMBERSHIP_ADMIN, hoa, "r")
        GrantRole(db)(root, "docs@example.test", Role.DOCUMENT_ADMIN, hoa, "r")
        GrantRole(db)(root, "finance-b@example.test", Role.FINANCIAL_ADMIN, otherHoa, "r")
        GrantRole(db)(root, "finance@example.test", Role.FINANCIAL_ADMIN, hoa, "r")
        fun actor(email: String) = (ResolveOperator(db)(email) as ResolveOperator.Result.Active).actor
        val content = fixture("transactions.csv")

        for (who in listOf("membership@example.test", "docs@example.test", "finance-b@example.test").map(::actor)) {
            accounts(who, hoa, fixture("accounts.csv"), true, "r") shouldBe ImportAccounts.Result.Forbidden
            transactions(who, hoa, "t.csv", content, true, "r") shouldBe ImportTransactions.Result.Forbidden
            ListBalances(db)(who, hoa) shouldBe ListBalances.Result.Forbidden
            AccountStatement(db)(who, hoa, "Unit 01") shouldBe AccountStatement.Result.Forbidden
            ReconcileBalances(db)(who, hoa, "e.csv", fixture("expected-balances.csv")) shouldBe ReconcileBalances.Result.Forbidden
        }

        val financeAdmin = actor("finance@example.test")
        accounts(financeAdmin, hoa, fixture("accounts.csv"), true, "r").shouldBeInstanceOf<ImportAccounts.Result.Imported>()
        transactions(financeAdmin, hoa, "t.csv", content, true, "r").shouldBeInstanceOf<ImportTransactions.Result.Imported>()

        // Another HOA cannot post to these accounts: its lookups see only its own.
        val otherAdmin = actor("finance-b@example.test")
        transactions(otherAdmin, otherHoa, "t.csv", csv("X-1,CH-1001,2026-01-01,assessment,Dues,1.00,"), true, "r")
            .shouldBeInstanceOf<ImportTransactions.Result.Conflicts>().conflicts.single() shouldContain "not mapped"
        accounts(otherAdmin, otherHoa, "unit_label,account\nUnit 01,CH-1001\n".toByteArray(), true, "r")
            .shouldBeInstanceOf<ImportAccounts.Result.Imported>()
        // Same spreadsheet ID in two HOAs, two separate accounts; the other HOA's ledger is untouched.
        transactions(otherAdmin, otherHoa, "t.csv", csv("A-2026Q1-01,CH-1001,2026-01-01,assessment,Dues,1.00,"), true, "r")
            .shouldBeInstanceOf<ImportTransactions.Result.Imported>()
        balance("Unit 01") shouldBe 0
        (ListBalances(db)(otherAdmin, otherHoa) as ListBalances.Result.Listed).totalCents shouldBe 100
    }
}
