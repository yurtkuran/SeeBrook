package cc.seebrook.app

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.MemoryStorage
import cc.seebrook.app.support.TestPdfs
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.db.generated.document.Tables.VERSION
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.db.generated.jobs.Tables.OUTBOX_JOB
import cc.seebrook.document.CreateCategory
import cc.seebrook.document.DocumentDetails
import cc.seebrook.document.DocumentVersionJob
import cc.seebrook.document.ListDocumentsForAdmin
import cc.seebrook.document.ProcessDocumentVersion
import cc.seebrook.document.UploadDocument
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.GrantRole
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.jobs.JobRunner
import cc.seebrook.platform.scan.MalwareScanner
import cc.seebrook.platform.scan.ScanResult
import cc.seebrook.platform.scan.ScannerUnavailableException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import cc.seebrook.db.generated.document.Tables.DOCUMENT_ as DOCUMENT

/** Upload, quarantine, scan and publish (GA-12, ED-38). Storage and scanner are fakes here. */
class DocumentUploadTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val hoa: UUID = Fixtures.hoa(db, "hoa-a")
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val storage = MemoryStorage()
    private var verdict: () -> ScanResult = { ScanResult.Clean("ClamAV test") }
    private val scanned = mutableListOf<ByteArray>()
    private val scanner = MalwareScanner { content -> scanned += content; verdict() }
    private val max = 1024 * 1024
    private val upload = UploadDocument(db, storage, max)
    private val jobs = JobRunner(db, mapOf(DocumentVersionJob.KIND to ProcessDocumentVersion(db, storage, scanner, max)))
    private val details = DocumentDetails(
        title = "  Bylaws 2026 ",
        description = "Amended bylaws",
        tags = listOf("bylaws", " Governance ", "BYLAWS"),
        documentDate = LocalDate.of(2026, 3, 1),
    )

    init {
        CreateCategory(db)(root, hoa, "Governing documents").shouldBeInstanceOf<CreateCategory.Result.Created>()
    }

    @AfterEach
    fun close() = db.close()

    private fun uploadPdf(content: ByteArray = TestPdfs.pdf(pages = 2), actor: UserActor = root) =
        upload(actor, hoa, "governing DOCUMENTS", details, "bylaws.pdf", content)

    private fun document() = db.dsl.selectFrom(DOCUMENT).fetchSingle()
    private fun version() = db.dsl.selectFrom(VERSION).fetchSingle()

    @Test
    fun `an upload is quarantined, then published after a clean scan`() {
        val uploaded = uploadPdf().shouldBeInstanceOf<UploadDocument.Result.Uploaded>()

        uploaded.pageCount shouldBe 2
        document().status shouldBe "processing"
        document().title shouldBe "Bylaws 2026"
        document().tags.toList() shouldBe listOf("bylaws", "Governance")
        version().state shouldBe "quarantined"
        storage.objects.keys.single() shouldStartWith "hoa/$hoa/quarantine/"

        jobs.drain() shouldBe 1

        document().status shouldBe "published"
        document().currentVersionId shouldBe uploaded.versionId
        version().state shouldBe "clean"
        version().scannedBy shouldBe "ClamAV test"
        // Moved out of quarantine to its permanent private location.
        storage.objects.keys.single() shouldBe "hoa/$hoa/documents/${uploaded.documentId}/${uploaded.versionId}.pdf"
        version().objectKey shouldBe storage.objects.keys.single()
        db.dsl.fetchCount(EVENT, EVENT.ACTION.eq("document.uploaded")) shouldBe 1
        db.dsl.fetchCount(EVENT, EVENT.ACTION.eq("document.version_published")) shouldBe 1
    }

    @Test
    fun `an infected upload is rejected, removed from storage and never published`() {
        verdict = { ScanResult.Infected("ClamAV test", "Eicar-Test-Signature") }
        uploadPdf()

        jobs.drain()

        document().status shouldBe "rejected"
        document().currentVersionId shouldBe null
        version().state shouldBe "rejected"
        version().rejectionReason shouldContain "Eicar-Test-Signature"
        storage.objects.keys.shouldBeEmpty()
        val event = db.dsl.selectFrom(EVENT).where(EVENT.ACTION.eq("document.version_rejected")).fetchSingle()
        event.reason shouldContain "malware detected"
        val rows = (ListDocumentsForAdmin(db)(root, hoa) as ListDocumentsForAdmin.Result.Listed).rows
        rows.single().rejectionReason shouldContain "malware"
    }

    @Test
    fun `while the scanner is down the file stays quarantined and is scanned on retry`() {
        verdict = { throw ScannerUnavailableException("down") }
        uploadPdf()

        jobs.drain() shouldBe 1
        document().status shouldBe "processing"
        version().state shouldBe "quarantined"
        db.dsl.select(OUTBOX_JOB.STATUS).from(OUTBOX_JOB).fetchSingle(OUTBOX_JOB.STATUS) shouldBe "queued"

        verdict = { ScanResult.Clean("ClamAV test") }
        db.dsl.update(OUTBOX_JOB).set(OUTBOX_JOB.RUN_AFTER, DSL.currentOffsetDateTime()).execute()
        jobs.drain() shouldBe 1
        document().status shouldBe "published"
    }

    @Test
    fun `a file changed in storage after upload is rejected`() {
        uploadPdf()
        storage.objects.replaceAll { _, _ -> TestPdfs.pdf(pages = 5) }

        jobs.drain()

        version().rejectionReason shouldContain "does not match"
        scanned.shouldBeEmpty()
    }

    @Test
    fun `invalid files and details are refused before anything is stored`() {
        uploadPdf("not a pdf".toByteArray()).shouldBeInstanceOf<UploadDocument.Result.InvalidFile>().reason shouldContain "not a PDF"
        uploadPdf(ByteArray(max + 1)).shouldBeInstanceOf<UploadDocument.Result.InvalidFile>()
        upload(root, hoa, "Governing documents", details.copy(title = " "), "x.pdf", TestPdfs.pdf())
            .shouldBeInstanceOf<UploadDocument.Result.InvalidDetails>().problems.single().first shouldBe "title"
        upload(root, hoa, "No such category", details, "x.pdf", TestPdfs.pdf()) shouldBe UploadDocument.Result.CategoryNotFound

        storage.objects.keys.shouldBeEmpty()
        db.dsl.fetchCount(DOCUMENT) shouldBe 0
        db.dsl.fetchCount(OUTBOX_JOB) shouldBe 0
    }

    @Test
    fun `if storage is down nothing is recorded`() {
        storage.unavailable = true
        runCatching { uploadPdf() }.isFailure shouldBe true
        db.dsl.fetchCount(DOCUMENT) shouldBe 0
    }

    @Test
    fun `only document administrators of the HOA may manage documents`() {
        val otherHoa = Fixtures.hoa(db, "hoa-b")
        CreateCategory(db)(root, otherHoa, "Minutes")
        listOf("membership@example.test", "docs-b@example.test").forEach {
            db.dsl.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, it).execute()
        }
        GrantRole(db)(root, "membership@example.test", Role.MEMBERSHIP_ADMIN, hoa, "r")
        GrantRole(db)(root, "docs-b@example.test", Role.DOCUMENT_ADMIN, otherHoa, "r")
        fun actor(email: String) = (ResolveOperator(db)(email) as ResolveOperator.Result.Active).actor

        uploadPdf(actor = actor("membership@example.test")) shouldBe UploadDocument.Result.Forbidden
        uploadPdf(actor = actor("docs-b@example.test")) shouldBe UploadDocument.Result.Forbidden
        CreateCategory(db)(actor("docs-b@example.test"), hoa, "Sneaky") shouldBe CreateCategory.Result.Forbidden
        // A category of another HOA cannot be used, even by Root, because lookups are HOA-scoped.
        upload(root, hoa, "Minutes", details, "x.pdf", TestPdfs.pdf()) shouldBe UploadDocument.Result.CategoryNotFound
        CreateCategory(db)(root, hoa, "GOVERNING documents") shouldBe CreateCategory.Result.AlreadyExists
    }
}
