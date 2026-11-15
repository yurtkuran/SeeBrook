package cc.seebrook.identity

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class OwnerCsvTest {
    private val header = OwnerCsv.COLUMNS.joinToString(",")
    private fun parse(vararg rows: String) = OwnerCsv.parse((listOf(header) + rows).joinToString("\n").toByteArray())
    private fun errors(vararg rows: String) = parse(*rows).shouldBeInstanceOf<OwnerCsv.Parsed.Invalid>().errors

    @Test
    fun `parses rows, normalizing emails and allowing quoted commas`() {
        val rows = parse(
            "Unit 01,1 Harbor Dr,,Ocean Isle Beach,NC,28469,Owner@Example.TEST,\"Smith, Pat\"",
            "Unit 02,2 Harbor Dr,Apt 2,Ocean Isle Beach,NC,28469-1234,,",
        ).shouldBeInstanceOf<OwnerCsv.Parsed.Valid>().rows

        rows shouldHaveSize 2
        rows[0].email shouldBe "owner@example.test"
        rows[0].name shouldBe "Smith, Pat"
        rows[0].property.addressLine2 shouldBe null
        rows[0].line shouldBe 2
        rows[1].email shouldBe null
    }

    @Test
    fun `accepts a UTF-8 byte order mark and columns in any order`() {
        val reordered = "owner_email,owner_name,unit_label,address_line1,address_line2,city,state_code,postal_code\n" +
            "a@example.test,,Unit 01,1 Harbor Dr,,Ocean Isle Beach,NC,28469"
        OwnerCsv.parse(("﻿" + reordered).toByteArray()).shouldBeInstanceOf<OwnerCsv.Parsed.Valid>()
    }

    @Test
    fun `reports every field problem with its line and column`() {
        val errors = errors(
            ",1 Harbor Dr,,Ocean Isle Beach,nc,2846,not-an-email,",
            "Unit 02,,,Ocean Isle Beach,NC,28469,,Name Without Email",
        )
        errors.map { it.line to it.column }.toSet() shouldBe setOf(
            2L to "unit_label", 2L to "state_code", 2L to "postal_code", 2L to "owner_email",
            3L to "address_line1", 3L to "owner_email",
        )
    }

    @Test
    fun `rows of one unit must agree, and an owner may not repeat`() {
        val errors = errors(
            "Unit 01,1 Harbor Dr,,Ocean Isle Beach,NC,28469,a@example.test,",
            "Unit 01,9 Other St,,Ocean Isle Beach,NC,28469,b@example.test,",
            "Unit 02,2 Harbor Dr,,Ocean Isle Beach,NC,28469,c@example.test,",
            "Unit 02,2 Harbor Dr,,Ocean Isle Beach,NC,28469,C@example.test,",
        )
        errors.map { it.line } shouldBe listOf(3L, 5L)
        errors[0].message shouldContain "differ from line 2"
        errors[1].message shouldContain "repeats line 4"
    }

    @Test
    fun `rejects a wrong header, wrong field count, empty file and bad encoding`() {
        OwnerCsv.parse("unit_label,city\nUnit 01,X".toByteArray())
            .shouldBeInstanceOf<OwnerCsv.Parsed.Invalid>().errors.single().message shouldContain "Missing"
        OwnerCsv.parse("$header,extra\n".toByteArray())
            .shouldBeInstanceOf<OwnerCsv.Parsed.Invalid>().errors.single().message shouldContain "Unknown"
        errors("Unit 01,1 Harbor Dr").single().message shouldContain "expected 8"
        errors().single().message shouldContain "no data rows"
        OwnerCsv.parse(byteArrayOf(0xC3.toByte(), 0x28))
            .shouldBeInstanceOf<OwnerCsv.Parsed.Invalid>().errors.single().message shouldContain "UTF-8"
        OwnerCsv.parse("$header\n\"unterminated".toByteArray()).shouldBeInstanceOf<OwnerCsv.Parsed.Invalid>()
        OwnerCsv.parse("unit_label,unit_label\n".toByteArray()).shouldBeInstanceOf<OwnerCsv.Parsed.Invalid>()
    }

    @Test
    fun `rejects oversized files`() {
        OwnerCsv.parse(ByteArray(OwnerCsv.MAX_BYTES + 1) { 'a'.code.toByte() })
            .shouldBeInstanceOf<OwnerCsv.Parsed.Invalid>().errors.single().message shouldContain "larger"
    }
}
