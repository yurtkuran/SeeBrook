package cc.seebrook.app

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.MemoryStorage
import cc.seebrook.app.support.TestPdfs
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.db.generated.document.Tables.CATEGORY
import cc.seebrook.db.generated.document.Tables.VERSION
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.document.ArchiveDocument
import cc.seebrook.document.CreateCategory
import cc.seebrook.document.DeleteCategory
import cc.seebrook.document.DetailChanges
import cc.seebrook.document.DocumentDetails
import cc.seebrook.document.DocumentQuery
import cc.seebrook.document.DocumentSort
import cc.seebrook.document.DocumentVersionJob
import cc.seebrook.document.GetOwnerDocument
import cc.seebrook.document.IssueDownloadLink
import cc.seebrook.document.ListOwnerDocuments
import cc.seebrook.document.ListVersions
import cc.seebrook.document.ProcessDocumentVersion
import cc.seebrook.document.ReassignDocuments
import cc.seebrook.document.RenameCategory
import cc.seebrook.document.ReplaceDocument
import cc.seebrook.document.RestoreDocument
import cc.seebrook.document.RestoreVersion
import cc.seebrook.document.UpdateDocumentDetails
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
import cc.seebrook.property.Ownerships
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import cc.seebrook.db.generated.document.Tables.DOCUMENT_ as DOCUMENT

/** Replace, archive, restore, version rollback, detail edits and category changes (GA-11, GA-13). */
class DocumentAdminTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val storage = MemoryStorage()
    private var verdict: ScanResult = ScanResult.Clean("ClamAV test")
    private val max = 1024 * 1024
    private val jobs = JobRunner(db, mapOf(DocumentVersionJob.KIND to ProcessDocumentVersion(db, storage, MalwareScanner { verdict }, max)))
    private val hoa = Fixtures.hoa(db, "hoa-a")
    private val unit = Fixtures.property(db, hoa, "Unit 01")
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }

    /** An owner of Unit 01, to see the library as owners do. */
    private val owner: UserActor = run {
        val id = requireNotNull(
            db.dsl.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, "owner@example.test").returning(USER_ACCOUNT.ID).fetchSingle().id,
        )
        db.transaction { Ownerships.grant(it, hoa, unit.propertyId, id) }
        UserActor(id)
    }

    init {
        CreateCategory(db)(root, hoa, "Governing documents")
        CreateCategory(db)(root, hoa, "Minutes")
    }

    @AfterEach
    fun close() = db.close()

    private fun upload(title: String = "Bylaws", pages: Int = 1, category: String = "Governing documents"): UUID {
        val result = UploadDocument(db, storage, max)(root, hoa, category, DocumentDetails(title, "About $title"), "$title.pdf", TestPdfs.pdf(pages))
        jobs.drain()
        return (result as UploadDocument.Result.Uploaded).documentId
    }

    private fun replace(document: UUID, pages: Int, process: Boolean = true): ReplaceDocument.Result =
        ReplaceDocument(db, storage, max)(root, hoa, document, "v$pages.pdf", TestPdfs.pdf(pages)).also { if (process) jobs.drain() }

    private fun ownerPages(document: UUID) = GetOwnerDocument(db)(owner, hoa, document)?.pageCount
    private fun ownerTitles() =
        (ListOwnerDocuments(db)(owner, hoa, DocumentQuery(sort = DocumentSort.TITLE, descending = false)) as ListOwnerDocuments.Result.Listed)
            .page.documents.map { it.title }

    private fun versions(document: UUID) = (ListVersions(db)(root, hoa, document) as ListVersions.Result.Listed).versions
    private fun audits(action: String) = db.dsl.selectFrom(EVENT).where(EVENT.ACTION.eq(action)).fetch()

    @Test
    fun `a replacement becomes current only after a clean scan, and earlier versions are kept`() {
        val doc = upload(pages = 1)
        val v1 = versions(doc).single().versionId

        replace(doc, pages = 2, process = false).shouldBeInstanceOf<ReplaceDocument.Result.Uploaded>()
        // While the new version is scanned, owners still get the current one.
        ownerPages(doc) shouldBe 1
        replace(doc, pages = 3, process = false) shouldBe ReplaceDocument.Result.AlreadyProcessing

        jobs.drain()
        ownerPages(doc) shouldBe 2
        val link = IssueDownloadLink(db, storage)(owner, hoa, doc) as IssueDownloadLink.Result.Issued
        val v2 = versions(doc).first().versionId
        link.url.path shouldContain "$v2.pdf"
        // Both files remain in private storage for restoration (GA-11).
        storage.objects.keys shouldBe setOf("hoa/$hoa/documents/$doc/$v1.pdf", "hoa/$hoa/documents/$doc/$v2.pdf")
        versions(doc).map { it.isCurrent } shouldBe listOf(true, false)
        audits("document.replacement_uploaded").size shouldBe 1
        audits("document.version_published").last().details.data() shouldContain "\"replacedCurrentVersion\": true"
    }

    @Test
    fun `a rejected replacement leaves the current version in place`() {
        val doc = upload(pages = 1)
        verdict = ScanResult.Infected("ClamAV test", "Eicar-Test-Signature")
        replace(doc, pages = 2)

        ownerPages(doc) shouldBe 1
        db.dsl.select(DOCUMENT.STATUS).from(DOCUMENT).fetchSingle(DOCUMENT.STATUS) shouldBe "published"
        versions(doc).first().state shouldBe "rejected"
        storage.objects.size shouldBe 1

        // Another attempt is allowed once the previous one is settled.
        verdict = ScanResult.Clean("ClamAV test")
        replace(doc, pages = 4).shouldBeInstanceOf<ReplaceDocument.Result.Uploaded>()
        ownerPages(doc) shouldBe 4
    }

    @Test
    fun `a document whose first upload was rejected can be uploaded again`() {
        verdict = ScanResult.Infected("ClamAV test", "Eicar-Test-Signature")
        val doc = upload()
        ownerPages(doc) shouldBe null

        verdict = ScanResult.Clean("ClamAV test")
        replace(doc, pages = 2).shouldBeInstanceOf<ReplaceDocument.Result.Uploaded>()
        ownerPages(doc) shouldBe 2
    }

    @Test
    fun `archived documents are hidden from owners and can be restored`() {
        val doc = upload("Bylaws")
        upload("Rules")
        val archive = ArchiveDocument(db)
        val restore = RestoreDocument(db)

        archive(root, hoa, doc, " ") shouldBe ArchiveDocument.Result.ReasonRequired
        archive(root, hoa, doc, "Superseded") shouldBe ArchiveDocument.Result.Archived
        ownerTitles() shouldBe listOf("Rules")
        GetOwnerDocument(db)(owner, hoa, doc) shouldBe null
        IssueDownloadLink(db, storage)(owner, hoa, doc) shouldBe IssueDownloadLink.Result.NotFound
        archive(root, hoa, doc, "again") shouldBe ArchiveDocument.Result.NotPublished("archived")
        // An archived document must be restored before it is replaced.
        replace(doc, pages = 2) shouldBe ReplaceDocument.Result.Archived

        restore(root, hoa, doc, "Still in force") shouldBe RestoreDocument.Result.Restored
        ownerTitles() shouldBe listOf("Bylaws", "Rules")
        restore(root, hoa, doc, "again") shouldBe RestoreDocument.Result.NotArchived("published")
        audits("document.archived").single().reason shouldBe "Superseded"
        audits("document.restored").single().reason shouldBe "Still in force"
        storage.objects.size shouldBe 2
    }

    @Test
    fun `an earlier version can be restored, and the rollback undone`() {
        val doc = upload(pages = 1)
        replace(doc, pages = 2)
        val (v2, v1) = versions(doc).map { it.versionId }
        val rollback = RestoreVersion(db)

        rollback(root, hoa, doc, v1, null) shouldBe RestoreVersion.Result.ReasonRequired
        rollback(root, hoa, doc, v1, "Wrong file uploaded") shouldBe RestoreVersion.Result.Restored
        ownerPages(doc) shouldBe 1
        rollback(root, hoa, doc, v1, "again") shouldBe RestoreVersion.Result.AlreadyCurrent
        rollback(root, hoa, doc, v2, "Undo") shouldBe RestoreVersion.Result.Restored
        ownerPages(doc) shouldBe 2

        val event = audits("document.version_restored").first()
        event.details.data() shouldContain v2.toString()
        event.details.data() shouldContain v1.toString()

        // Rejected versions, other documents' versions, and rollbacks during a scan are refused.
        verdict = ScanResult.Infected("ClamAV test", "Eicar-Test-Signature")
        replace(doc, pages = 3)
        val rejected = versions(doc).first().versionId
        rollback(root, hoa, doc, rejected, "x") shouldBe RestoreVersion.Result.VersionNotClean("rejected")
        val other = upload("Other")
        val otherVersion = versions(other).single().versionId
        rollback(root, hoa, doc, otherVersion, "x") shouldBe RestoreVersion.Result.VersionNotFound
        verdict = ScanResult.Clean("ClamAV test")
        replace(doc, pages = 4, process = false)
        rollback(root, hoa, doc, v1, "x") shouldBe RestoreVersion.Result.AlreadyProcessing
    }

    @Test
    fun `details can be edited and a document moved to another category, with before and after audited`() {
        val doc = upload("Bylaws")
        val update = UpdateDocumentDetails(db)

        update(root, hoa, doc, DetailChanges(title = " ")).shouldBeInstanceOf<UpdateDocumentDetails.Result.InvalidDetails>()
        update(root, hoa, doc, DetailChanges(categoryName = "Nope")) shouldBe UpdateDocumentDetails.Result.CategoryNotFound
        update(root, hoa, doc, DetailChanges(title = "Bylaws")) shouldBe UpdateDocumentDetails.Result.NoChange
        update(
            root, hoa, doc,
            DetailChanges(title = "Bylaws 2026", tags = listOf("bylaws"), documentDate = LocalDate.of(2026, 1, 5), categoryName = "minutes"),
        ) shouldBe UpdateDocumentDetails.Result.Updated

        val seen = GetOwnerDocument(db)(owner, hoa, doc)!!
        seen.title shouldBe "Bylaws 2026"
        seen.category.name shouldBe "Minutes"
        seen.documentDate shouldBe LocalDate.of(2026, 1, 5)
        update(root, hoa, doc, DetailChanges(clearDocumentDate = true)) shouldBe UpdateDocumentDetails.Result.Updated
        GetOwnerDocument(db)(owner, hoa, doc)!!.documentDate shouldBe null

        val details = audits("document.details_updated").first().details.data()
        details shouldContain "\"before\""
        details shouldContain "Governing documents"
        details shouldContain "Bylaws 2026"
    }

    @Test
    fun `categories are renamed in place and deleted only when empty, archived documents included`() {
        val doc = upload("Old minutes", category = "Minutes")
        val minutesId = db.dsl.select(CATEGORY.ID).from(CATEGORY).where(CATEGORY.NAME.eq("Minutes")).fetchSingle(CATEGORY.ID)
        val rename = RenameCategory(db)
        val delete = DeleteCategory(db)

        rename(root, hoa, "minutes", "Governing DOCUMENTS") shouldBe RenameCategory.Result.AlreadyExists
        rename(root, hoa, "minutes", "  Board   minutes ") shouldBe RenameCategory.Result.Renamed
        rename(root, hoa, "board minutes", "BOARD minutes") shouldBe RenameCategory.Result.Renamed // case only
        rename(root, hoa, "nope", "x") shouldBe RenameCategory.Result.NotFound
        // Stable ID: the document is still in the same category.
        GetOwnerDocument(db)(owner, hoa, doc)!!.category.id shouldBe minutesId
        GetOwnerDocument(db)(owner, hoa, doc)!!.category.name shouldBe "BOARD minutes"

        ArchiveDocument(db)(root, hoa, doc, "Old")
        delete(root, hoa, "board minutes", "Tidy up") shouldBe DeleteCategory.Result.NotEmpty(1)
        delete(root, hoa, "board minutes", null) shouldBe DeleteCategory.Result.ReasonRequired

        ReassignDocuments(db)(root, hoa, "board minutes", "board minutes") shouldBe ReassignDocuments.Result.SameCategory
        ReassignDocuments(db)(root, hoa, "board minutes", "Governing documents") shouldBe ReassignDocuments.Result.Moved(1)
        delete(root, hoa, "board minutes", "Merged into governing documents") shouldBe DeleteCategory.Result.Deleted
        delete(root, hoa, "board minutes", "again") shouldBe DeleteCategory.Result.NotFound
        db.dsl.select(DOCUMENT.CATEGORY_ID).from(DOCUMENT).fetchSingle(DOCUMENT.CATEGORY_ID) shouldBe
            db.dsl.select(CATEGORY.ID).from(CATEGORY).fetchSingle(CATEGORY.ID)
        audits("document.category_deleted").single().reason shouldBe "Merged into governing documents"
    }

    @Test
    fun `only document administrators of the HOA may change documents, and only that HOA's`() {
        val doc = upload()
        val v1 = versions(doc).single().versionId
        val otherHoa = Fixtures.hoa(db, "hoa-b")
        CreateCategory(db)(root, otherHoa, "Minutes")
        listOf("membership@example.test", "docs-b@example.test").forEach {
            db.dsl.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, it).execute()
        }
        GrantRole(db)(root, "membership@example.test", Role.MEMBERSHIP_ADMIN, hoa, "r")
        GrantRole(db)(root, "docs-b@example.test", Role.DOCUMENT_ADMIN, otherHoa, "r")
        fun actor(email: String) = (ResolveOperator(db)(email) as ResolveOperator.Result.Active).actor

        for (who in listOf(actor("membership@example.test"), actor("docs-b@example.test"), owner)) {
            ReplaceDocument(db, storage, max)(who, hoa, doc, "x.pdf", TestPdfs.pdf()) shouldBe ReplaceDocument.Result.Forbidden
            ArchiveDocument(db)(who, hoa, doc, "r") shouldBe ArchiveDocument.Result.Forbidden
            RestoreVersion(db)(who, hoa, doc, v1, "r") shouldBe RestoreVersion.Result.Forbidden
            UpdateDocumentDetails(db)(who, hoa, doc, DetailChanges(title = "x")) shouldBe UpdateDocumentDetails.Result.Forbidden
            ListVersions(db)(who, hoa, doc) shouldBe ListVersions.Result.Forbidden
            RenameCategory(db)(who, hoa, "Minutes", "x") shouldBe RenameCategory.Result.Forbidden
            DeleteCategory(db)(who, hoa, "Minutes", "r") shouldBe DeleteCategory.Result.Forbidden
        }

        // A document ID used under another HOA is not found, even by Root (HOA-scoped lookups).
        ReplaceDocument(db, storage, max)(root, otherHoa, doc, "x.pdf", TestPdfs.pdf()) shouldBe ReplaceDocument.Result.NotFound
        ArchiveDocument(db)(root, otherHoa, doc, "r") shouldBe ArchiveDocument.Result.NotFound
        RestoreVersion(db)(root, otherHoa, doc, v1, "r") shouldBe RestoreVersion.Result.NotFound
        UpdateDocumentDetails(db)(root, otherHoa, doc, DetailChanges(title = "x")) shouldBe UpdateDocumentDetails.Result.NotFound
        ListVersions(db)(root, otherHoa, doc) shouldBe ListVersions.Result.NotFound
        // Nor can a document be moved into another HOA's category.
        UpdateDocumentDetails(db)(actor("docs-b@example.test"), otherHoa, doc, DetailChanges(categoryName = "Minutes")) shouldBe
            UpdateDocumentDetails.Result.NotFound
        ReassignDocuments(db)(root, otherHoa, "Minutes", "Governing documents") shouldBe
            ReassignDocuments.Result.CategoryNotFound("Governing documents")

        ownerPages(doc) shouldBe 1
        db.dsl.fetchCount(VERSION) shouldBe 1
    }
}
