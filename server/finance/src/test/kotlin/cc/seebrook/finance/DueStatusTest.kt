package cc.seebrook.finance

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DueStatusTest {
    private val today = LocalDate.of(2026, 7, 10)
    private fun d(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun status(vararg items: Pair<Long, LocalDate>) =
        DueStatus.of(items.sumOf { it.first }, items.map { Charge(it.first, it.second) }, today, null)

    @Test
    fun `credits pay the oldest charges first, so overdue is what past-due charges leave unpaid`() {
        val s = status(45000L to d(1, 1), -45000L to d(1, 5), 45000L to d(4, 1), -30000L to d(4, 3), 45000L to d(7, 1), 45000L to d(10, 1))
        s.balanceCents shouldBe 105000
        s.amountDueCents shouldBe 105000
        s.overdueCents shouldBe 60000 // 3 x 450 past due, 750 paid
        s.notYetDueCents shouldBe 45000
        s.nextDueDate shouldBe d(10, 1)
        s.creditCents shouldBe 0
        s.asOfDate shouldBe today
    }

    @Test
    fun `a charge due today is not overdue, and overdue never exceeds the balance`() {
        status(45000L to today).let {
            it.overdueCents shouldBe 0
            it.notYetDueCents shouldBe 45000
            it.nextDueDate shouldBe today
        }
        // An advance payment dated later still counts against what is past due.
        status(45000L to d(1, 1), 45000L to d(12, 1), -60000L to d(12, 5)).let {
            it.overdueCents shouldBe 0
            it.notYetDueCents shouldBe 30000
        }
    }

    @Test
    fun `a credit balance owes nothing`() {
        val s = status(45000L to d(1, 1), -50000L to d(1, 5))
        s.balanceCents shouldBe -5000
        s.amountDueCents shouldBe 0
        s.overdueCents shouldBe 0
        s.notYetDueCents shouldBe 0
        s.creditCents shouldBe 5000
        s.nextDueDate shouldBe null
    }

    @Test
    fun `Excel amounts lose only floating-point noise, never a real fraction of a cent`() {
        RegisterWorkbook.centsOf(450.0) shouldBe 45000
        RegisterWorkbook.centsOf(0.1 + 0.2) shouldBe 30
        RegisterWorkbook.centsOf(-1234.5) shouldBe -123450
        RegisterWorkbook.centsOf(1.005) shouldBe null
        RegisterWorkbook.centsOf(Double.NaN) shouldBe null
        RegisterWorkbook.centsOf(1e30) shouldBe null
    }
}
