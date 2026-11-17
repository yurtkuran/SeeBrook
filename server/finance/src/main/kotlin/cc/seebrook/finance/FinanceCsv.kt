package cc.seebrook.finance

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/** A problem in a file. [line] is the physical line of the row (the header is line 1); 0 for the whole file. */
data class RowError(val line: Long, val column: String?, val message: String)

/** One data row: its line and its trimmed values by column; blank values are absent. */
internal class CsvRow(val line: Long, private val values: Map<String, String>) {
    operator fun get(column: String): String? = values[column]
}

/**
 * Reads the finance CSV files (ED-41): UTF-8, RFC 4180, optional byte-order mark, a header with
 * exactly [columns] in any order. Returns every whole-file problem at once.
 */
internal object FinanceCsv {
    const val MAX_BYTES = 2 * 1_048_576
    const val MAX_ROWS = 20_000

    sealed interface Read {
        data class Rows(val rows: List<CsvRow>) : Read
        data class Invalid(val errors: List<RowError>) : Read
    }

    fun read(bytes: ByteArray, columns: List<String>, optional: Set<String> = emptySet()): Read {
        fun invalid(message: String) = Read.Invalid(listOf(RowError(0, null, message)))
        if (bytes.size > MAX_BYTES) return invalid("File is larger than ${MAX_BYTES / 1_048_576} MiB")
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
                .removePrefix("﻿")
        } catch (_: CharacterCodingException) {
            return invalid("File is not valid UTF-8 text (in Excel, save as \"CSV UTF-8\")")
        }

        val header: List<String>
        val records: List<CSVRecord>
        try {
            val parser = CSVFormat.RFC4180.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get()
                .parse(text.reader())
            header = parser.headerNames
            records = parser.records
        } catch (e: IllegalArgumentException) {
            return invalid("Malformed CSV: ${e.message}")
        } catch (e: IllegalStateException) {
            return invalid("Malformed CSV: ${e.message}")
        } catch (e: UncheckedIOException) {
            return invalid("Malformed CSV: ${e.cause?.message ?: e.message}")
        } catch (e: IOException) {
            return invalid("Malformed CSV: ${e.message}")
        }

        val missing = columns - optional - header.toSet()
        val unknown = header - columns.toSet()
        if (missing.isNotEmpty() || unknown.isNotEmpty()) {
            return invalid(
                buildString {
                    append("Header must contain: ${columns.joinToString(",")}.")
                    if (missing.isNotEmpty()) append(" Missing: ${missing.joinToString(",")}.")
                    if (unknown.isNotEmpty()) append(" Unknown: ${unknown.joinToString(",")}.")
                },
            )
        }
        if (records.isEmpty()) return invalid("File has no data rows")
        if (records.size > MAX_ROWS) return invalid("File has more than $MAX_ROWS rows")

        val errors = mutableListOf<RowError>()
        val rows = records.mapNotNull { record ->
            val line = record.recordNumber + 1
            if (!record.isConsistent) {
                errors += RowError(line, null, "has ${record.size()} fields, expected ${header.size}")
                null
            } else {
                CsvRow(line, header.associateWith { record.get(it).trim() }.filterValues { it.isNotEmpty() })
            }
        }
        return if (errors.isEmpty()) Read.Rows(rows) else Read.Invalid(errors)
    }
}

/** Dates as ISO (2026-03-01) or US month/day/year (3/1/2026), as Excel exports them. */
internal object CsvDates {
    private val iso = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private val us = DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT)

    fun parse(text: String): LocalDate? = listOf(iso, us).firstNotNullOfOrNull {
        try {
            LocalDate.parse(text, it)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/** Exact US dollar amounts as integer cents; never floating point. */
object Money {
    private val grouped = Regex("""^\d{1,3}(,\d{3})*(\.\d{1,2})?$""")
    private val plain = Regex("""^\d+(\.\d{1,2})?$""")
    private const val MAX_DOLLAR_DIGITS = 9

    /**
     * Parses `150`, `150.5`, `1,250.00`, `$1,250.00`, `-150.00`, `-$150.00` or `($150.00)` exactly.
     * Returns null for anything else, including more than two decimals: amounts are never rounded.
     */
    fun parseCents(text: String): Long? {
        var s = text.trim()
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1).trim()
        }
        if (s.startsWith("-")) {
            if (negative) return null
            negative = true
            s = s.substring(1)
        }
        s = s.removePrefix("$")
        if (!grouped.matches(s) && !plain.matches(s)) return null
        val digits = s.replace(",", "")
        val whole = digits.substringBefore(".")
        if (whole.length > MAX_DOLLAR_DIGITS) return null
        val cents = digits.substringAfter(".", "").padEnd(2, '0')
        val value = whole.toLong() * 100 + cents.toLong()
        return if (negative) -value else value
    }

    /** `$1,250.00`, `-$150.00`. */
    fun format(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = Math.abs(cents)
        return String.format(Locale.US, "%s$%,d.%02d", sign, abs / 100, abs % 100)
    }
}
