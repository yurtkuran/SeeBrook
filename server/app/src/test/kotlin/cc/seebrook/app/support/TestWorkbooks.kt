package cc.seebrook.app.support

import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Synthetic register workbooks in the HOA's layout (ED-42): an `Owners` sheet behind the `owners`
 * name, `<year> income` and `<year> transactions`. Every value is made up; balances on the income
 * sheet are computed the way the sheet's formulas do (positive is a credit).
 */
object TestWorkbooks {
    data class Entry(
        val id: String?,
        val payee: String,
        val date: LocalDate,
        val amount: Double?,
        val category: String = "HOA Dues",
        val type: String = "Deposit",
        /** `Seebrook Ref`: the row records this online payment (ED-44). */
        val seebrookRef: String? = null,
    )

    data class Spec(
        val year: Int = 2026,
        val units: List<String>,
        val monthlyDues: Double = 150.0,
        /** Months due so far (Jan..monthsDue). */
        val monthsDue: Int = 3,
        val previous: Map<String, Double> = emptyMap(),
        val register: List<Entry> = emptyList(),
        /** Overrides of the computed `<year> Balance`, to make the sheet disagree. */
        val currentOverride: Map<String, Double> = emptyMap(),
        val includeOwnersName: Boolean = true,
    )

    fun build(spec: Spec): ByteArray = XSSFWorkbook().use { wb ->
        val dateStyle = wb.createCellStyle().apply { dataFormat = wb.createDataFormat().getFormat("mm/dd/yyyy") }
        fun Row.set(col: Int, value: Any?) {
            val cell = createCell(col)
            when (value) {
                null -> {}
                is String -> cell.setCellValue(value)
                is Number -> cell.setCellValue(value.toDouble())
                is LocalDate -> cell.setCellValue(value).also { cell.cellStyle = dateStyle }
                else -> error("unsupported $value")
            }
        }
        fun Sheet.row(index: Int): Row = getRow(index) ?: createRow(index)

        val owners = wb.createSheet("Owners")
        owners.row(0).apply { set(0, "Unit"); set(1, "Last Name") }
        spec.units.forEachIndexed { i, unit -> owners.row(i + 1).apply { set(0, unit); set(1, "Owner ${i + 1}") } }
        if (spec.includeOwnersName) {
            wb.createName().apply { nameName = "owners"; refersToFormula = "Owners!\$A\$1:\$N\$${spec.units.size + 1}" }
        }

        wb.createSheet("${spec.year} budget").row(0).set(0, "Budget") // a same-year sheet without transactionId

        val year = spec.year
        val income = wb.createSheet("$year income")
        val monthCols = (4..15)
        val totalDue = spec.monthlyDues * spec.monthsDue
        monthCols.forEachIndexed { i, col ->
            income.row(0).set(col, i + 1)
            income.row(1).set(col, if (i < spec.monthsDue) spec.monthlyDues else 0)
            income.row(2).set(col, LocalDate.of(year, i + 1, 1))
        }
        income.row(2).set(1, year)
        income.row(4).set(16, totalDue)
        income.row(5).apply {
            set(2, "Condo"); set(3, "${year - 1} Balance"); set(16, "$year Balance")
            Month.entries.forEachIndexed { i, m -> set(4 + i, m.getDisplayName(TextStyle.SHORT, Locale.US)) }
        }
        val inYear = spec.register.filter { it.date.year == year }
        spec.units.forEachIndexed { i, unit ->
            val previous = spec.previous[unit] ?: 0.0
            val monthly = (1..12).map { m -> inYear.filter { it.payee == unit && it.date.monthValue == m }.sumOf { it.amount ?: 0.0 } }
            income.row(6 + i).apply {
                set(1, "Owner ${i + 1}"); set(2, unit); set(3, previous)
                monthly.forEachIndexed { m, v -> set(4 + m, v) }
                set(16, spec.currentOverride[unit] ?: (previous + monthly.sum() - totalDue))
            }
        }
        income.row(6 + spec.units.size).apply { set(2, "T.OTAL"); set(16, 0) }

        val register = wb.createSheet("$year transactions")
        register.row(3).apply {
            listOf(
                "transactionId", "ID", "Posted Date", "Transaction Date", "Transaction Type", "Account", "Check/Ref #", "Amount",
                "Bank Balance", "Running Balance", "Balance", "Payee ID", "Payee", "Budget Item", "Category", "Topic", "Year", "Month", "Comment",
                "Seebrook Ref",
            ).forEachIndexed { col, label -> set(col, label) }
        }
        spec.register.forEachIndexed { i, e ->
            register.row(4 + i).apply {
                set(0, e.id); set(1, 101); set(2, e.date); set(3, e.date); set(4, e.type); set(7, e.amount)
                set(11, e.payee); set(12, "Owner"); set(14, e.category); set(15, "Income"); set(16, e.date.year)
                set(18, "private note"); set(19, e.seebrookRef)
            }
        }
        ByteArrayOutputStream().also { wb.write(it) }.toByteArray()
    }
}
