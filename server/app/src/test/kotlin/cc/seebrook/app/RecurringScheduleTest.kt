package cc.seebrook.app

import cc.seebrook.finance.BankCalendar
import cc.seebrook.finance.Frequency
import cc.seebrook.finance.RecurringSchedule
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** Due dates and business days for recurring charges (ED-59). */
class RecurringScheduleTest {
    private fun d(text: String) = LocalDate.parse(text)

    @Test
    fun `Federal Reserve holidays, with Sunday holidays observed on Monday and Saturday ones not moved`() {
        BankCalendar.holidays(2026) shouldContainAll listOf(
            d("2026-01-01"), d("2026-01-19"), d("2026-02-16"), d("2026-05-25"), d("2026-06-19"),
            d("2026-09-07"), d("2026-10-12"), d("2026-11-11"), d("2026-11-26"), d("2026-12-25"),
        )
        // July 4, 2026 is a Saturday: not moved, and the Friday before is a business day.
        BankCalendar.holidays(2026).contains(d("2026-07-03")) shouldBe false
        BankCalendar.isBusinessDay(d("2026-07-03")) shouldBe true
        // July 4, 2027 is a Sunday: observed Monday the 5th.
        BankCalendar.holidays(2027).contains(d("2027-07-05")) shouldBe true
    }

    @Test
    fun `a due date on a weekend or holiday is charged on the next business day`() {
        BankCalendar.chargeDay(d("2027-01-01")) shouldBe d("2027-01-04") // New Year's Day (Friday) → Monday
        BankCalendar.chargeDay(d("2026-08-01")) shouldBe d("2026-08-03") // Saturday → Monday
        BankCalendar.chargeDay(d("2027-07-04")) shouldBe d("2027-07-06") // Sunday, then the observed Monday → Tuesday
        BankCalendar.chargeDay(d("2026-12-01")) shouldBe d("2026-12-01") // a Tuesday
    }

    @Test
    fun `monthly, quarterly and annual due dates follow the HOA's day and anchor month`() {
        val schedule = RecurringSchedule(UUID.randomUUID(), dayOfMonth = 1, anchorMonth = 1, frequencies = Frequency.entries.toSet())
        schedule.firstDueAfter(d("2026-12-15"), Frequency.MONTHLY) shouldBe d("2027-01-01")
        schedule.firstDueAfter(d("2027-01-01"), Frequency.MONTHLY) shouldBe d("2027-02-01")
        schedule.firstDueAfter(d("2027-02-10"), Frequency.QUARTERLY) shouldBe d("2027-04-01")
        schedule.firstDueAfter(d("2027-02-10"), Frequency.ANNUAL) shouldBe d("2028-01-01")
        schedule.following(d("2027-04-01"), Frequency.QUARTERLY) shouldBe d("2027-07-01")

        val fifteenth = RecurringSchedule(UUID.randomUUID(), dayOfMonth = 15, anchorMonth = 2, frequencies = setOf(Frequency.QUARTERLY))
        fifteenth.firstDueAfter(d("2026-12-15"), Frequency.QUARTERLY) shouldBe d("2027-02-15")
        fifteenth.isDue(d("2027-05-15"), Frequency.QUARTERLY) shouldBe true
        fifteenth.isDue(d("2027-06-15"), Frequency.QUARTERLY) shouldBe false
    }

    @Test
    fun `a plan never charges on the day it is set up, but a due date whose charge day is still ahead counts`() {
        val schedule = RecurringSchedule(UUID.randomUUID(), dayOfMonth = 1, anchorMonth = 1, frequencies = setOf(Frequency.MONTHLY))
        // Set up on the 1st (a Tuesday): today's date is not charged; the next is.
        schedule.firstChargeableAfter(d("2026-12-01"), Frequency.MONTHLY) shouldBe d("2027-01-01")
        // Set up on Saturday August 1: its charge day is Monday the 3rd, still ahead.
        schedule.firstChargeableAfter(d("2026-08-01"), Frequency.MONTHLY) shouldBe d("2026-08-01")
    }
}
