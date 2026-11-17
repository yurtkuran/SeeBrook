package cc.seebrook.finance

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ParsingTest {
    @Test
    fun `amounts are parsed exactly in the forms Excel exports`() {
        mapOf(
            "450" to 45000L, "450.5" to 45050L, "450.05" to 45005L, "1,250.00" to 125000L, "$1,250.00" to 125000L,
            "-150.00" to -15000L, "-$150.00" to -15000L, "($150.00)" to -15000L, "(150)" to -15000L, "0.01" to 1L,
            "999999999.99" to 99999999999L,
        ).forEach { (text, cents) -> Money.parseCents(text) shouldBe cents }
        listOf("", "abc", "1.234", "12,50.00", "1,2345", "$-150", "--1", "(-1)", "1e3", "1 000", "1000000000", "€5", ".50", "5.")
            .forEach { Money.parseCents(it) shouldBe null }
    }

    @Test
    fun `amounts are formatted as dollars`() {
        Money.format(125000) shouldBe "$1,250.00"
        Money.format(-5) shouldBe "-$0.05"
        Money.format(0) shouldBe "$0.00"
    }

    private fun parse(vararg lines: String) = TransactionCsv.parse(
        ("transaction_id,account,date,type,description,amount,due_date\n" + lines.joinToString("\n")).toByteArray(),
    )

    private fun errors(vararg lines: String) = parse(*lines).shouldBeInstanceOf<TransactionCsv.Parsed.Invalid>().errors

    @Test
    fun `a valid file is parsed with ISO and US dates`() {
        val rows = parse(
            "A-1,CH-1001,12/17/2025,Assessment,Q1 dues,$450.00,1/1/2026",
            "P-1,CH-1001,2026-01-10,payment,Check payment,(450.00),",
        ).shouldBeInstanceOf<TransactionCsv.Parsed.Valid>().rows
        rows[0].values shouldBe TransactionValues(TransactionType.ASSESSMENT, LocalDate.of(2025, 12, 17), LocalDate.of(2026, 1, 1), "Q1 dues", 45000)
        rows[1].values.amountCents shouldBe -45000
        rows[1].values.dueDate shouldBe null
        rows[1].line shouldBe 3
    }

    @Test
    fun `every problem is reported with its line and column`() {
        val errors = errors(
            "A-1,CH-1001,2026-02-30,assessment,Dues,450.00,",
            ",CH-1001,2026-01-01,fee,Dues,450.005,",
            "A-3,CH-1001,2026-01-01,payment,Paid,450.00,",
            "A-4,CH-1001,2026-01-01,assessment,Dues,0,",
            "A-5,CH-1001,2026-01-01,waiver,Waived,-25.00,2026-02-01",
            "A-1,CH-1001,2026-01-01,assessment,Dues,450.00,",
        )
        errors.map { it.line to it.column } shouldContainExactly listOf(
            2L to "date", 3L to "transaction_id", 3L to "type", 3L to "amount", 4L to "amount", 5L to "amount", 6L to "due_date",
            7L to "transaction_id",
        )
        errors.first { it.line == 4L }.message shouldContain "payment needs a negative amount"
        errors.last().message shouldContain "repeats line 2"
    }

    @Test
    fun `the header must match and the file must be readable`() {
        TransactionCsv.parse("transaction_id,account,date,type,description,amount,extra\n".toByteArray())
            .shouldBeInstanceOf<TransactionCsv.Parsed.Invalid>().errors.single().message shouldContain "Unknown: extra"
        // due_date is optional as a column.
        TransactionCsv.parse("transaction_id,account,date,type,description,amount\nA,B,2026-01-01,assessment,D,1.00\n".toByteArray())
            .shouldBeInstanceOf<TransactionCsv.Parsed.Valid>()
        TransactionCsv.parse(byteArrayOf(0xC3.toByte(), 0x28))
            .shouldBeInstanceOf<TransactionCsv.Parsed.Invalid>().errors.single().message shouldContain "UTF-8"
        TransactionCsv.parse("transaction_id,account,date,type,description,amount\n".toByteArray())
            .shouldBeInstanceOf<TransactionCsv.Parsed.Invalid>().errors.single().message shouldContain "no data rows"
    }
}
