package cc.seebrook.finance

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** How often a recurring plan charges (REQUIREMENTS: monthly, quarterly or annual). */
enum class Frequency(val code: String, val months: Long) {
    MONTHLY("monthly", 1),
    QUARTERLY("quarterly", 3),
    ANNUAL("annual", 12),
    ;

    companion object {
        fun fromCode(code: String): Frequency? = entries.firstOrNull { it.code == code.trim().lowercase() }
    }
}

/**
 * When an HOA's recurring plans charge (ED-59): on [dayOfMonth] (1–28, so every month has it), for
 * the [frequencies] owners may choose. Quarterly plans charge in [anchorMonth] and every third
 * month after it; annual plans in [anchorMonth]. A due date on a weekend or bank holiday is
 * charged on the next business day ([BankCalendar]).
 */
data class RecurringSchedule(
    val hoaId: UUID,
    val dayOfMonth: Int,
    val anchorMonth: Int,
    val frequencies: Set<Frequency>,
) {
    init {
        require(dayOfMonth in 1..28) { "day of month must be 1–28" }
        require(anchorMonth in 1..12) { "anchor month must be 1–12" }
        require(frequencies.isNotEmpty()) { "at least one frequency" }
    }

    /** Whether [date] is a due date for [frequency]. */
    fun isDue(date: LocalDate, frequency: Frequency): Boolean =
        date.dayOfMonth == dayOfMonth && Math.floorMod(date.monthValue - anchorMonth, frequency.months.toInt()) == 0

    /** The first due date for [frequency] strictly after [date]. */
    fun firstDueAfter(date: LocalDate, frequency: Frequency): LocalDate {
        var candidate = date.withDayOfMonth(dayOfMonth).let { if (it.isAfter(date)) it else it.plusMonths(1) }
        while (!isDue(candidate, frequency)) candidate = candidate.plusMonths(1)
        return candidate
    }

    /** The due date after [due] for [frequency]. */
    fun following(due: LocalDate, frequency: Frequency): LocalDate = due.plusMonths(frequency.months).withDayOfMonth(dayOfMonth)

    /**
     * The first due date for [frequency] whose charge day is after [today], so a plan set up or
     * resumed today never charges the same day.
     */
    fun firstChargeableAfter(today: LocalDate, frequency: Frequency): LocalDate {
        var due = firstDueAfter(today.minusDays(7), frequency)
        while (!BankCalendar.chargeDay(due).isAfter(today)) due = following(due, frequency)
        return due
    }
}

/**
 * Business days for charges (ED-59): Monday to Friday except the Federal Reserve's bank holidays,
 * when ACH does not settle. A holiday on a Sunday is observed the next Monday; one on a Saturday is
 * not moved (the Federal Reserve stays open the Friday before).
 */
object BankCalendar {
    fun isBusinessDay(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && date !in holidays(date.year)

    /** The day a due date is charged: the date itself, or the next business day. */
    fun chargeDay(due: LocalDate): LocalDate {
        var day = due
        while (!isBusinessDay(day)) day = day.plusDays(1)
        return day
    }

    fun holidays(year: Int): Set<LocalDate> {
        fun fixed(month: Month, day: Int) = LocalDate.of(year, month, day).let { if (it.dayOfWeek == DayOfWeek.SUNDAY) it.plusDays(1) else it }
        fun nth(month: Month, n: Int, weekday: DayOfWeek) =
            LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, weekday))
        fun last(month: Month, weekday: DayOfWeek) = LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(weekday))
        return setOf(
            fixed(Month.JANUARY, 1), // New Year's Day
            nth(Month.JANUARY, 3, DayOfWeek.MONDAY), // Birthday of Martin Luther King, Jr.
            nth(Month.FEBRUARY, 3, DayOfWeek.MONDAY), // Washington's Birthday
            last(Month.MAY, DayOfWeek.MONDAY), // Memorial Day
            fixed(Month.JUNE, 19), // Juneteenth
            fixed(Month.JULY, 4), // Independence Day
            nth(Month.SEPTEMBER, 1, DayOfWeek.MONDAY), // Labor Day
            nth(Month.OCTOBER, 2, DayOfWeek.MONDAY), // Columbus Day
            fixed(Month.NOVEMBER, 11), // Veterans Day
            nth(Month.NOVEMBER, 4, DayOfWeek.THURSDAY), // Thanksgiving Day
            fixed(Month.DECEMBER, 25), // Christmas Day
        )
    }
}
