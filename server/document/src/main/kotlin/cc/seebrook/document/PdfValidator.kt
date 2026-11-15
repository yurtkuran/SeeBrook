package cc.seebrook.document

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.IOException

/**
 * Our own checks on an uploaded file before it is stored or scanned (GA-12, ED-38): not empty,
 * within the size limit, a PDF signature, a PDF that actually opens, not encrypted, at least one
 * page. The malware scan is separate.
 */
object PdfValidator {
    sealed interface Result {
        data class Valid(val pageCount: Int) : Result
        data class Invalid(val reason: String) : Result
    }

    private val SIGNATURE = "%PDF-".toByteArray()

    fun check(content: ByteArray, maxBytes: Int): Result {
        if (content.isEmpty()) return Result.Invalid("the file is empty")
        if (content.size > maxBytes) return Result.Invalid("the file is larger than ${maxBytes / (1024 * 1024)} MB")
        // The PDF header may follow up to 1 KB of leading bytes.
        if (indexOf(content, SIGNATURE, limit = 1024) < 0) return Result.Invalid("the file is not a PDF")
        return try {
            Loader.loadPDF(content).use { pdf ->
                when {
                    pdf.isEncrypted -> Result.Invalid("the PDF is encrypted or has restrictions; upload an unprotected copy")
                    pdf.numberOfPages < 1 -> Result.Invalid("the PDF has no pages")
                    else -> Result.Valid(pdf.numberOfPages)
                }
            }
        } catch (_: InvalidPasswordException) {
            Result.Invalid("the PDF is password protected; upload an unprotected copy")
        } catch (_: IOException) {
            Result.Invalid("the PDF is damaged or uses an unsupported format")
        }
    }

    private fun indexOf(content: ByteArray, pattern: ByteArray, limit: Int): Int {
        val last = minOf(limit, content.size - pattern.size)
        for (i in 0..last) {
            if (pattern.indices.all { content[i + it] == pattern[it] }) return i
        }
        return -1
    }
}
