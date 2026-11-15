package cc.seebrook.app.support

import cc.seebrook.platform.storage.ObjectStorage
import cc.seebrook.platform.storage.StorageUnavailableException
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Small generated PDFs for tests. */
object TestPdfs {
    fun pdf(pages: Int = 1): ByteArray = PDDocument().use { doc ->
        repeat(pages) { doc.addPage(PDPage()) }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }
}

/** In-memory [ObjectStorage] for tests that are not about the storage provider itself. */
class MemoryStorage : ObjectStorage {
    val objects = ConcurrentHashMap<String, ByteArray>()
    var unavailable = false

    private fun check() {
        if (unavailable) throw StorageUnavailableException("storage down (test)")
    }

    override fun put(key: String, content: ByteArray, contentType: String) {
        check()
        objects[key] = content
    }

    override fun get(key: String): ByteArray? = check().let { objects[key] }

    override fun copy(fromKey: String, toKey: String) {
        check()
        objects[toKey] = requireNotNull(objects[fromKey]) { "no object $fromKey" }
    }

    override fun delete(key: String) {
        check()
        objects.remove(key)
    }

    override fun signedDownloadUrl(key: String, validFor: Duration, fileName: String): URI =
        URI("https://storage.test/$key?expires=${validFor.seconds}&name=${URLEncoder.encode(fileName, Charsets.UTF_8)}")
}
