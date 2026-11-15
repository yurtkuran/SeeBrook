package cc.seebrook.identity

import cc.seebrook.property.PropertyInput
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** A problem in the file. [line] is the physical line of the row (the header is line 1); 0 for the whole file. */
data class RowError(val line: Long, val column: String?, val message: String)

/** One owner row: the property, plus the HOA-verified owner (absent for a property-only row). */
data class OwnerRow(val line: Long, val property: PropertyInput, val email: String?, val name: String?)

/**
 * Parses and validates the owner/property CSV (ED-34). Validation is all-or-nothing: any error
 * rejects the whole file, and every error found is reported at once.
 */
object OwnerCsv {
    val COLUMNS = listOf(
        "unit_label", "address_line1", "address_line2", "city", "state_code", "postal_code", "owner_email", "owner_name",
    )
    const val MAX_BYTES = 1_048_576
    const val MAX_ROWS = 5_000
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    sealed interface Parsed {
        data class Valid(val rows: List<OwnerRow>) : Parsed
        data class Invalid(val errors: List<RowError>) : Parsed
    }

    fun parse(bytes: ByteArray): Parsed {
        if (bytes.size > MAX_BYTES) return invalid("File is larger than ${MAX_BYTES / 1024} KiB")
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
                .removePrefix("﻿")
        } catch (e: CharacterCodingException) {
            return invalid("File is not valid UTF-8 text")
        }

        val records: List<CSVRecord>
        val header: List<String>
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

        val missing = COLUMNS - header.toSet()
        val unknown = header - COLUMNS.toSet()
        if (missing.isNotEmpty() || unknown.isNotEmpty()) {
            return invalid(
                buildString {
                    append("Header must contain exactly: ${COLUMNS.joinToString(",")}.")
                    if (missing.isNotEmpty()) append(" Missing: ${missing.joinToString(",")}.")
                    if (unknown.isNotEmpty()) append(" Unknown: ${unknown.joinToString(",")}.")
                },
            )
        }
        if (records.isEmpty()) return invalid("File has no data rows")
        if (records.size > MAX_ROWS) return invalid("File has more than $MAX_ROWS rows")

        val errors = mutableListOf<RowError>()
        val rows = records.mapNotNull { record -> parseRow(record, errors) }
        errors += crossRowErrors(rows)
        return if (errors.isEmpty()) Parsed.Valid(rows) else Parsed.Invalid(errors.sortedBy { it.line })
    }

    private fun invalid(message: String) = Parsed.Invalid(listOf(RowError(0, null, message)))

    private fun parseRow(record: CSVRecord, errors: MutableList<RowError>): OwnerRow? {
        // Header is line 1, so the first data row is line 2 (for files without multi-line fields).
        val line = record.recordNumber + 1
        if (!record.isConsistent) {
            errors += RowError(line, null, "has ${record.size()} fields, expected ${COLUMNS.size}")
            return null
        }
        fun value(column: String): String? = record.get(column).trim().takeIf { it.isNotEmpty() }

        val property = PropertyInput(
            unitLabel = value("unit_label").orEmpty(),
            addressLine1 = value("address_line1").orEmpty(),
            addressLine2 = value("address_line2"),
            city = value("city").orEmpty(),
            stateCode = value("state_code").orEmpty(),
            postalCode = value("postal_code").orEmpty(),
        )
        val before = errors.size
        property.problems().forEach { (column, message) -> errors += RowError(line, column, message) }

        val email = value("owner_email")?.let(EmailAddress::normalize)
        val name = value("owner_name")
        if (email != null && (email.length > 320 || !EMAIL.matches(email))) {
            errors += RowError(line, "owner_email", "is not a valid email address")
        }
        if (name != null && name.length > 200) errors += RowError(line, "owner_name", "is longer than 200 characters")
        if (email == null && name != null) errors += RowError(line, "owner_email", "is required when owner_name is given")

        return if (errors.size == before) OwnerRow(line, property, email, name) else null
    }

    /** Rows of one unit must agree on its details; an owner may appear only once per unit. */
    private fun crossRowErrors(rows: List<OwnerRow>): List<RowError> = buildList {
        rows.groupBy { it.property.unitLabel }.forEach { (unit, unitRows) ->
            val first = unitRows.first()
            unitRows.drop(1).filter { it.property != first.property }.forEach {
                add(RowError(it.line, null, "details for unit '$unit' differ from line ${first.line}"))
            }
            unitRows.filter { it.email != null }.groupBy { it.email }.values.filter { it.size > 1 }.forEach { dupes ->
                dupes.drop(1).forEach {
                    add(RowError(it.line, "owner_email", "repeats line ${dupes.first().line} for unit '$unit'"))
                }
            }
        }
    }
}
