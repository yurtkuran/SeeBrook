package cc.seebrook.document

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class PdfValidatorTest {
    private val max = 1024 * 1024

    private fun pdf(pages: Int = 2, protect: StandardProtectionPolicy? = null): ByteArray =
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage()) }
            protect?.let { doc.protect(it) }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    private fun invalid(content: ByteArray) =
        PdfValidator.check(content, max).shouldBeInstanceOf<PdfValidator.Result.Invalid>().reason

    @Test
    fun `a normal PDF is valid and its pages are counted`() {
        PdfValidator.check(pdf(pages = 3), max) shouldBe PdfValidator.Result.Valid(3)
    }

    @Test
    fun `empty, oversized and non-PDF files are rejected`() {
        invalid(ByteArray(0)) shouldContain "empty"
        invalid(ByteArray(max + 1) { 'a'.code.toByte() }) shouldContain "larger than 1 MB"
        invalid("<html>not a pdf</html>".toByteArray()) shouldContain "not a PDF"
        invalid(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(100)) shouldContain "not a PDF" // a ZIP
    }

    @Test
    fun `a damaged PDF is rejected`() {
        invalid("%PDF-1.7\n this is not really a PDF body".toByteArray()) shouldContain "damaged"
    }

    @Test
    fun `password-protected and restricted PDFs are rejected`() {
        invalid(pdf(protect = StandardProtectionPolicy("owner-pw", "user-pw", AccessPermission()))) shouldContain "password"
        // Opens without a password but is still encrypted (owner restrictions).
        invalid(pdf(protect = StandardProtectionPolicy("owner-pw", "", AccessPermission()))) shouldContain "encrypted"
    }
}
