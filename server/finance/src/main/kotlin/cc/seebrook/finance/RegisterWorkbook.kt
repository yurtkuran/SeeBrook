package cc.seebrook.finance

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.poi.ss.SpreadsheetVersion
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Converts the HOA's register workbook into the canonical import files (ED-42), so the bookkeeper
 * keeps working in Excel. Reads cached cell values only; formulas are never evaluated and the
 * workbook is never changed.
 *
 * The layout it expects, by name rather than position where the workbook allows:
 *  - **Units:** the first column of the defined name `owners` (its "Unit" header is skipped).
 *  - **`<year> income`:** a header row with the unit column, `<year-1> Balance`, `Jan`…`Dec` and
 *    `<year> Balance`; one row per unit. Above it, a row of each month's due date and, directly
 *    above that, the dues due for each month so far (0 for months not yet due). Balances there
 *    are positive for a credit, the opposite of Seebrook's sign.
 *  - **`<year> …` register:** a header row with `transactionId`, `Transaction Date`, `Amount`,
 *    `Payee ID`, `Category` and optionally `Transaction Type`. Rows whose payee is a unit and whose
 *    date is in the year are the unit's money (positive: received from the owner); others are
 *    left out, exactly as the income sheet's own formulas do.
 *
 * It produces:
 *  - one carried-forward adjustment per unit from `<year-1> Balance` (`OPEN-<year>-<unit>`);
 *  - one dues assessment per unit and month due so far (`DUES-<year>-<MM>-<unit>`);
 *  - one transaction per register row, keyed by its `transactionId`: money received is a payment
 *    (a `Late Fee`-type credit is a waiver), money charged is a late fee for `Late Fee` category
 *    rows and an adjustment otherwise; 0 or blank voids it (ED-42);
 *  - the expected balances from `<year> Balance`, and an account mapping (unit = account).
 *
 * Before writing anything it checks, unit by unit, that the converted transactions add up to the
 * sheet's own `<year> Balance`; any difference rejects the conversion.
 */
object RegisterWorkbook {
    const val MAX_BYTES = 20L * 1024 * 1024

    data class Summary(
        val year: Int,
        val units: Int,
        val duesMonths: List<Month>,
        val duesCharges: Int,
        val openingBalances: Int,
        val payments: Int,
        val lateFees: Int,
        val waivers: Int,
        val adjustments: Int,
        /** Register rows with amount 0 or blank: they void an imported transaction, or are skipped. */
        val zeroRows: Int,
        /** transactionIds (or row numbers) of unit rows dated outside the year; the sheet leaves them out too. */
        val outsideYear: List<String>,
        /** Register rows whose payee is not a unit: expenses and other income. */
        val otherRows: Int,
    )

    class Output(val transactionsCsv: ByteArray, val expectedBalancesCsv: ByteArray, val accountsCsv: ByteArray, val summary: Summary)

    sealed interface Result {
        class Converted(val output: Output) : Result
        data class Invalid(val errors: List<String>) : Result
    }

    fun convert(content: ByteArray, year: Int): Result {
        if (content.size > MAX_BYTES) return Result.Invalid(listOf("The workbook is larger than 20 MiB"))
        val workbook = try {
            XSSFWorkbook(ByteArrayInputStream(content))
        } catch (e: Exception) {
            return Result.Invalid(listOf("Not a readable .xlsx workbook (${e.javaClass.simpleName}); save it as an Excel Workbook (.xlsx)"))
        }
        return workbook.use { Converter(it, year).run() }
    }

    private class Converter(private val workbook: Workbook, private val year: Int) {
        private val errors = mutableListOf<String>()
        private val rows = mutableListOf<TransactionRow>()

        fun run(): Result {
            val units = units() ?: return Result.Invalid(errors)
            val income = income(units) ?: return Result.Invalid(errors)
            val register = register(units.toSet()) ?: return Result.Invalid(errors)
            if (errors.isNotEmpty()) return Result.Invalid(errors)

            for (unit in units) {
                val opening = -income.previous.getValue(unit)
                rows += row("OPEN-$year-$unit", unit, TransactionType.ADJUSTMENT, LocalDate.of(year - 1, 12, 31), null,
                    "Balance carried forward from ${year - 1}", opening)
                income.dues.forEach { (month, due) ->
                    rows += row("DUES-$year-%02d-$unit".format(month.value), unit, TransactionType.ASSESSMENT, due.date, due.date,
                        "${month.getDisplayName(TextStyle.FULL, Locale.US)} $year HOA dues", due.cents)
                }
            }
            rows += register.rows

            // The income sheet's own arithmetic must come out the same, unit by unit.
            val totals = rows.groupBy { it.account }.mapValues { (_, r) -> r.sumOf { it.values.amountCents } }
            for (unit in units) {
                val expected = -income.current.getValue(unit)
                val actual = totals[unit] ?: 0L
                if (actual != expected) {
                    errors += "Unit $unit: the converted transactions total ${Money.format(actual)}, but the sheet's " +
                        "$year Balance means ${Money.format(expected)} (Seebrook sign: positive is owed)"
                }
            }
            if (errors.isNotEmpty()) return Result.Invalid(errors)

            val sorted = rows.sortedBy { it.externalId }
            val summary = Summary(
                year = year,
                units = units.size,
                duesMonths = income.dues.keys.toList(),
                duesCharges = income.dues.size * units.size,
                openingBalances = units.count { income.previous.getValue(it) != 0L },
                payments = register.rows.count { it.values.type == TransactionType.PAYMENT && it.values.amountCents != 0L },
                lateFees = register.rows.count { it.values.type == TransactionType.LATE_FEE && it.values.amountCents != 0L },
                waivers = register.rows.count { it.values.type == TransactionType.WAIVER && it.values.amountCents != 0L },
                adjustments = register.rows.count { it.values.type == TransactionType.ADJUSTMENT && it.values.amountCents != 0L },
                zeroRows = register.rows.count { it.values.amountCents == 0L },
                outsideYear = register.outsideYear,
                otherRows = register.otherRows,
            )
            return Result.Converted(
                Output(
                    transactionsCsv = csv(TransactionCsv.COLUMNS, sorted.map { r ->
                        val v = r.values
                        listOf(r.externalId, r.account, v.date.toString(), v.type.code, v.description, plain(v.amountCents), v.dueDate?.toString().orEmpty())
                    }),
                    expectedBalancesCsv = csv(listOf("account", "balance"), units.sorted().map { listOf(it, plain(-income.current.getValue(it))) }),
                    accountsCsv = csv(listOf("unit_label", "account"), units.sorted().map { listOf(it, it) }),
                    summary = summary,
                ),
            )
        }

        private fun row(id: String, unit: String, type: TransactionType, date: LocalDate, due: LocalDate?, description: String, cents: Long) =
            TransactionRow(0, id, unit, TransactionValues(type, date, due, description.take(200), cents))

        // ---- Units

        private fun units(): List<String>? {
            val name = workbook.getName("owners")
                ?: return null.also { errors += "No defined name 'owners' (the unit list, first column = unit)" }
            val area = try {
                AreaReference(name.refersToFormula, SpreadsheetVersion.EXCEL2007)
            } catch (e: Exception) {
                return null.also { errors += "The defined name 'owners' is not a cell range" }
            }
            val sheet = workbook.getSheet(area.firstCell.sheetName)
                ?: return null.also { errors += "The defined name 'owners' points at a missing sheet" }
            val column = area.firstCell.col.toInt()
            val units = (area.firstCell.row..area.lastCell.row)
                .mapNotNull { r -> sheet.getRow(r)?.getCell(column).value().text() }
                .filterNot { it.equals("Unit", ignoreCase = true) }
            units.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { errors += "Unit $it appears more than once in 'owners'" }
            units.filterNot(::validIdentifier).forEach { errors += "Unit '${it.take(70)}' in 'owners' is longer than 64 characters" }
            if (units.isEmpty()) errors += "The defined name 'owners' lists no units"
            return units.takeIf { errors.isEmpty() }
        }

        // ---- Income sheet

        private data class Due(val date: LocalDate, val cents: Long)
        private class Income(val previous: Map<String, Long>, val current: Map<String, Long>, val dues: Map<Month, Due>)

        private fun income(units: List<String>): Income? {
            val sheetName = "$year income"
            val sheet = workbook.getSheet(sheetName) ?: return null.also { errors += "No sheet '$sheetName'" }
            val previousLabel = "${year - 1} balance"
            val currentLabel = "$year balance"
            val header = sheet.findHeader(30) { previousLabel in it && currentLabel in it && "jan" in it }
                ?: return null.also { errors += "'$sheetName': no header row with '${year - 1} Balance', 'Jan'…'Dec' and '$year Balance'" }
            val (headerRow, columns) = header
            val months = Month.entries.associateWith { m ->
                columns[m.getDisplayName(TextStyle.SHORT, Locale.US).lowercase()]
                    ?: return null.also { errors += "'$sheetName': no '${m.getDisplayName(TextStyle.SHORT, Locale.US)}' column in the header row" }
            }
            val previousCol = columns.getValue(previousLabel)
            val currentCol = columns.getValue(currentLabel)
            val unitCol = previousCol - 1

            // The due-date row: every month cell is a date in that month of the year.
            val dueRow = (headerRow - 1 downTo 1).firstOrNull { r ->
                months.all { (month, col) -> sheet.getRow(r)?.getCell(col).value().date()?.let { it.year == year && it.month == month } == true }
            } ?: return null.also { errors += "'$sheetName': no row of due dates (one per month of $year) above the header" }
            val dues = linkedMapOf<Month, Due>()
            months.forEach { (month, col) ->
                val cell = sheet.getRow(dueRow - 1)?.getCell(col)
                val cents = cell.value().cents(blankIsZero = true)
                when {
                    cents == null || cents < 0 ->
                        errors += "'$sheetName' row $dueRow, ${month.getDisplayName(TextStyle.SHORT, Locale.US)}: dues due is not an amount"
                    cents > 0 -> dues[month] = Due(requireNotNull(sheet.getRow(dueRow)?.getCell(col).value().date()), cents)
                }
            }

            val previous = mutableMapOf<String, Long>()
            val current = mutableMapOf<String, Long>()
            val unitSet = units.toSet()
            val seen = mutableSetOf<String>()
            for (r in headerRow + 1..sheet.lastRowNum) {
                val row = sheet.getRow(r) ?: continue
                val unit = row.getCell(unitCol).value().text()?.takeIf { it in unitSet } ?: continue
                if (!seen.add(unit)) {
                    errors += "'$sheetName' row ${r + 1}: unit $unit appears again"
                    continue
                }
                val prev = row.getCell(previousCol).value().cents(blankIsZero = true)
                val cur = row.getCell(currentCol).value().cents(blankIsZero = false)
                if (prev == null) errors += "'$sheetName' row ${r + 1} (unit $unit): ${year - 1} Balance is not an amount"
                if (cur == null) errors += "'$sheetName' row ${r + 1} (unit $unit): $year Balance is not an amount"
                if (prev != null && cur != null) {
                    previous[unit] = prev
                    current[unit] = cur
                }
            }
            units.filter { it !in seen }.forEach { errors += "'$sheetName': no row for unit $it" }
            return if (errors.isEmpty()) Income(previous, current, dues) else null
        }

        // ---- Register

        private class Register(val rows: List<TransactionRow>, val outsideYear: List<String>, val otherRows: Int)

        private fun register(units: Set<String>): Register? {
            val candidates = workbook.sheetIterator().asSequence()
                .filter { it.sheetName.startsWith("$year ") && it.sheetName != "$year income" }
                .mapNotNull { s -> s.findHeader(10) { "transactionid" in it }?.let { s to it } }
                .toList()
            val (sheet, header) = candidates.singleOrNull() ?: return null.also {
                errors += if (candidates.isEmpty()) "No '$year …' sheet with a 'transactionId' column" else "More than one '$year …' sheet has a 'transactionId' column"
            }
            val (headerRow, columns) = header
            val name = sheet.sheetName
            fun column(label: String) = columns[label.lowercase()].also { if (it == null) errors += "'$name': no '$label' column" }
            val idCol = column("transactionId")
            val dateCol = column("Transaction Date")
            val amountCol = column("Amount")
            val payeeCol = column("Payee ID")
            val categoryCol = column("Category")
            val typeCol = columns["transaction type"]
            if (idCol == null || dateCol == null || amountCol == null || payeeCol == null || categoryCol == null) return null

            val rows = mutableListOf<TransactionRow>()
            val outside = mutableListOf<String>()
            var other = 0
            for (r in headerRow + 1..sheet.lastRowNum) {
                val row = sheet.getRow(r) ?: continue
                val line = r + 1
                val payee = row.getCell(payeeCol).value().text()
                if (payee == null || payee !in units) {
                    if (row.any { it.value() != Value.Blank }) other++
                    continue
                }
                val id = row.getCell(idCol).value().text()
                val where = "'$name' row $line${id?.let { " ($it)" }.orEmpty()}"
                val date = row.getCell(dateCol).value().date()
                if (date == null) {
                    errors += "$where: Transaction Date is not a date"
                    continue
                }
                if (date.year != year) {
                    outside += id ?: "row $line"
                    continue
                }
                if (id == null) {
                    errors += "$where: unit $payee row has no transactionId"
                    continue
                }
                val amount = row.getCell(amountCol).value().cents(blankIsZero = true)
                if (amount == null) {
                    errors += "$where: Amount is not an amount in dollars and cents"
                    continue
                }
                val category = row.getCell(categoryCol).value().text().orEmpty()
                val kind = typeCol?.let { row.getCell(it).value().text() }.orEmpty()
                val cents = -amount // the register's positive (money in) is Seebrook's negative (less owed)
                val lateFee = category.equals("Late Fee", ignoreCase = true)
                val (type, description) = when {
                    cents < 0 && kind.equals("Late Fee", ignoreCase = true) -> TransactionType.WAIVER to "Late fee waived"
                    cents < 0 -> TransactionType.PAYMENT to (if (category.isEmpty()) "Payment" else "Payment: $category")
                    cents > 0 && lateFee -> TransactionType.LATE_FEE to "Late fee"
                    cents > 0 -> TransactionType.ADJUSTMENT to (if (category.isEmpty()) "Adjustment" else "Adjustment: $category")
                    // 0 voids: the import keeps the transaction's other values.
                    lateFee -> TransactionType.LATE_FEE to "Late fee"
                    else -> TransactionType.PAYMENT to "Payment"
                }
                rows += row(id, payee, type, date, null, description, cents)
            }
            return Register(rows, outside, other)
        }
    }

    // ---- Cells

    private sealed interface Value {
        data object Blank : Value
        data class Number(val value: Double) : Value
        data class Text(val value: String) : Value
        data object Other : Value
    }

    /** The cell's value, or for a formula its cached result. */
    private fun Cell?.value(): Value {
        if (this == null) return Value.Blank
        val type = if (cellType == CellType.FORMULA) cachedFormulaResultType else cellType
        return when (type) {
            CellType.NUMERIC -> Value.Number(numericCellValue)
            CellType.STRING -> stringCellValue.trim().let { if (it.isEmpty()) Value.Blank else Value.Text(it) }
            CellType.BLANK -> Value.Blank
            else -> Value.Other
        }
    }

    private fun Value.text(): String? = when (this) {
        is Value.Text -> value
        is Value.Number -> if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()
        else -> null
    }

    private fun Value.date(): LocalDate? = when (this) {
        is Value.Number -> if (DateUtil.isValidExcelDate(value)) DateUtil.getLocalDateTime(value).toLocalDate() else null
        is Value.Text -> CsvDates.parse(value)
        else -> null
    }

    private fun Value.cents(blankIsZero: Boolean): Long? = when (this) {
        is Value.Number -> centsOf(value)
        is Value.Text -> Money.parseCents(value)
        Value.Blank -> if (blankIsZero) 0L else null
        Value.Other -> null
    }

    private val NOISE = BigDecimal("0.000001")

    /**
     * Excel stores amounts as binary floating point, so a computed 0.30 may read as
     * 0.30000000000000004. Noise below a millionth of a dollar is removed; any other fraction of a
     * cent is not an amount, and is never rounded.
     */
    internal fun centsOf(value: Double): Long? {
        if (!value.isFinite()) return null
        val exact = BigDecimal.valueOf(value)
        val cents = exact.setScale(2, RoundingMode.HALF_UP)
        if (exact.subtract(cents).abs() > NOISE) return null
        return runCatching { cents.movePointRight(2).longValueExact() }.getOrNull()
    }

    /** Lower-cased header text to column, for the first row within [maxRows] matching [accept]. */
    private fun Sheet.findHeader(maxRows: Int, accept: (Map<String, Int>) -> Boolean): Pair<Int, Map<String, Int>>? {
        for (r in 0..minOf(maxRows, lastRowNum)) {
            val row = getRow(r) ?: continue
            val labels = linkedMapOf<String, Int>()
            row.forEach { cell -> (cell.value() as? Value.Text)?.let { labels.putIfAbsent(it.value.lowercase(), cell.columnIndex) } }
            if (accept(labels)) return r to labels
        }
        return null
    }

    private fun plain(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val a = abs(cents)
        return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }

    private fun csv(header: List<String>, records: List<List<String>>): ByteArray {
        val out = StringWriter()
        CSVPrinter(out, CSVFormat.DEFAULT.builder().setRecordSeparator("\n").setHeader(*header.toTypedArray()).get()).use { printer ->
            records.forEach { printer.printRecord(it) }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }
}
