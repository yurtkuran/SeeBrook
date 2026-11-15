package cc.seebrook.app

import cc.seebrook.app.support.TestPdfs
import cc.seebrook.platform.config.StorageConfig
import cc.seebrook.platform.scan.ClamdScanner
import cc.seebrook.platform.scan.ScanResult
import cc.seebrook.platform.scan.ScannerUnavailableException
import cc.seebrook.platform.storage.S3ObjectStorage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The real storage and scanner adapters against the same containers used locally (ED-38). */
class StorageAndScannerTest {
    companion object {
        private val s3mock: GenericContainer<*> by lazy {
            GenericContainer<Nothing>("adobe/s3mock:5.2.3").apply {
                withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "test-bucket")
                withExposedPorts(9090)
                waitingFor(Wait.forListeningPort())
                start()
            }
        }

        private val clamav: GenericContainer<*> by lazy {
            // The Debian variant is multi-architecture (the plain tag is amd64 only).
            GenericContainer<Nothing>("clamav/clamav:1.5.4-debian").apply {
                withExposedPorts(3310)
                // Ready only once the signature database is loaded; the port can open earlier.
                waitingFor(Wait.forLogMessage(".*clamd started.*", 1).withStartupTimeout(Duration.ofMinutes(5)))
                start()
            }
        }

        /** The standard antivirus test file: harmless, detected by every scanner. */
        private const val EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*"
    }

    private val storage by lazy {
        S3ObjectStorage(
            StorageConfig(
                endpoint = "http://${s3mock.host}:${s3mock.getMappedPort(9090)}",
                region = "us-east-1",
                bucket = "test-bucket",
                accessKeyId = "test",
                secretAccessKey = "test-secret",
                pathStyle = true,
            ),
        )
    }

    @Test
    fun `storage puts, gets, copies, deletes and signs download links`() {
        val pdf = TestPdfs.pdf()
        storage.put("hoa/x/quarantine/a.pdf", pdf, "application/pdf")
        storage.get("hoa/x/quarantine/a.pdf")?.toList() shouldBe pdf.toList()
        storage.get("hoa/x/missing.pdf") shouldBe null

        storage.copy("hoa/x/quarantine/a.pdf", "hoa/x/documents/d/v.pdf")
        storage.delete("hoa/x/quarantine/a.pdf")
        storage.delete("hoa/x/quarantine/a.pdf") // already gone: no error
        storage.get("hoa/x/quarantine/a.pdf") shouldBe null

        val url = storage.signedDownloadUrl("hoa/x/documents/d/v.pdf", Duration.ofMinutes(5), "Bylaws 2026.pdf")
        url.query shouldContain "X-Amz-Expires=300"
        val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofByteArray())
        response.statusCode() shouldBe 200
        response.body().toList() shouldBe pdf.toList()
    }

    @Test
    fun `ClamAV passes a clean PDF and detects the EICAR test file`() {
        val scanner = ClamdScanner(clamav.host, clamav.getMappedPort(3310))

        scanner.scan(TestPdfs.pdf()).shouldBeInstanceOf<ScanResult.Clean>().engine shouldStartWith "ClamAV 1.5.4"
        scanner.scan(EICAR.toByteArray()).shouldBeInstanceOf<ScanResult.Infected>().signature.lowercase() shouldContain "eicar"
    }

    @Test
    fun `an unreachable scanner gives no verdict`() {
        shouldThrow<ScannerUnavailableException> { ClamdScanner("127.0.0.1", 1).scan(TestPdfs.pdf()) }
    }
}
