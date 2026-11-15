package cc.seebrook.app.http

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.MemoryStorage
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPdfs
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.document.CreateCategory
import cc.seebrook.document.DocumentDetails
import cc.seebrook.document.DocumentQuery
import cc.seebrook.document.DocumentVersionJob
import cc.seebrook.document.ListOwnerDocuments
import cc.seebrook.document.ProcessDocumentVersion
import cc.seebrook.document.UploadDocument
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.jobs.JobRunner
import cc.seebrook.platform.scan.MalwareScanner
import cc.seebrook.platform.scan.ScanResult
import cc.seebrook.property.Ownerships
import cc.seebrook.shared.api.AcceptInvitationRequest
import cc.seebrook.shared.api.DocumentCategoriesResponse
import cc.seebrook.shared.api.DocumentResponse
import cc.seebrook.shared.api.DocumentsPageResponse
import cc.seebrook.shared.api.DownloadLinkResponse
import cc.seebrook.shared.api.ErrorResponse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * M3 exit criterion over HTTP: an owner sees only the current published PDFs of HOAs where they
 * actively own a property (GA-10), can search, sort and page them, and downloads through
 * short-lived signed links that are audited.
 */
class DocumentLibraryTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val storage = MemoryStorage()
    private var verdict: ScanResult = ScanResult.Clean("ClamAV test")
    private val jobs = JobRunner(
        db,
        mapOf(DocumentVersionJob.KIND to ProcessDocumentVersion(db, storage, MalwareScanner { verdict }, 1024 * 1024)),
    )
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val hoaA = Fixtures.hoa(db, "hoa-a")
    private val hoaB = Fixtures.hoa(db, "hoa-b")
    private val unitA1 = Fixtures.property(db, hoaA, "Unit 01")
    private val unitB1 = Fixtures.property(db, hoaB, "Unit 01")
    private val ownerA = TestAuth.token("uid-a", "a@example.test")
    private val ownerB = TestAuth.token("uid-b", "b@example.test")

    init {
        listOf(hoaA to "Governing documents", hoaA to "Minutes", hoaA to "Empty", hoaB to "Minutes").forEach { (hoa, name) ->
            CreateCategory(db)(root, hoa, name)
        }
    }

    @AfterEach
    fun close() = db.close()

    /** Uploads and processes a document; returns its ID. Scanner [clean] decides if it publishes. */
    private fun document(
        hoa: UUID,
        category: String,
        title: String,
        description: String = "About $title",
        tags: List<String> = emptyList(),
        date: LocalDate? = null,
        clean: Boolean = true,
        process: Boolean = true,
    ): UUID {
        val result = UploadDocument(db, storage, 1024 * 1024)(
            root, hoa, category, DocumentDetails(title, description, tags, date), "upload-$title.pdf", TestPdfs.pdf(),
        ) as UploadDocument.Result.Uploaded
        verdict = if (clean) ScanResult.Clean("ClamAV test") else ScanResult.Infected("ClamAV test", "Eicar-Test-Signature")
        if (process) jobs.drain()
        return result.documentId
    }

    private fun ApplicationTestBuilder.client(withStorage: Boolean = true): HttpClient {
        application { seebrookModule(db, TestAuth.verifier, storage.takeIf { withStorage }) }
        return createClient { install(ContentNegotiation) { json() } }
    }

    private suspend fun HttpClient.becomeOwner(token: String, unit: Fixtures.Unit, email: String) {
        val invitation = IssueInvitation(db)(unit.hoaId, unit.propertyId, email).token
        post("/v1/invitations/accept") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(AcceptInvitationRequest(invitation))
        }.status shouldBe HttpStatusCode.OK
    }

    private suspend fun HttpClient.list(token: String, hoa: UUID, vararg params: Pair<String, Any>): HttpResponse =
        get("/v1/hoas/$hoa/documents") {
            bearerAuth(token)
            params.forEach { (k, v) -> parameter(k, v) }
        }

    private suspend fun HttpClient.titles(token: String, hoa: UUID, vararg params: Pair<String, Any>): List<String> =
        list(token, hoa, *params).also { it.status shouldBe HttpStatusCode.OK }
            .body<DocumentsPageResponse>().documents.map { it.title }

    private suspend fun HttpClient.link(token: String, hoa: UUID, document: UUID): HttpResponse =
        post("/v1/hoas/$hoa/documents/$document/download-link") { bearerAuth(token) }

    @Test
    fun `owners see only published documents with their current version details`() = testApplication {
        val http = client()
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        val bylaws = document(hoaA, "Governing documents", "Bylaws 2026", tags = listOf("bylaws"), date = LocalDate.of(2026, 3, 1))
        document(hoaA, "Minutes", "Infected minutes", clean = false)
        document(hoaA, "Minutes", "Still scanning", process = false)

        val page = http.list(ownerA, hoaA).body<DocumentsPageResponse>()
        page.total shouldBe 1
        val doc = page.documents.single()
        doc.id shouldBe bylaws.toString()
        doc.title shouldBe "Bylaws 2026"
        doc.categoryName shouldBe "Governing documents"
        doc.tags shouldBe listOf("bylaws")
        doc.documentDate shouldBe "2026-03-01"
        doc.pageCount shouldBe 1
        Instant.parse(doc.uploadedAt) // ISO-8601 instant

        http.get("/v1/hoas/$hoaA/documents/$bylaws") { bearerAuth(ownerA) }.body<DocumentResponse>().title shouldBe "Bylaws 2026"
        // Only categories with a published document; "Minutes" holds only unpublished ones.
        http.get("/v1/hoas/$hoaA/document-categories") { bearerAuth(ownerA) }.body<DocumentCategoriesResponse>()
            .categories.map { it.name to it.documentCount } shouldBe listOf("Governing documents" to 1)
    }

    @Test
    fun `search matches title, description, category and tags, every word must match`() = testApplication {
        val http = client()
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        document(hoaA, "Governing documents", "Bylaws 2026", description = "Amended rules", tags = listOf("governance"))
        document(hoaA, "Minutes", "March board meeting", description = "Pool budget approved")
        document(hoaA, "Minutes", "Annual meeting", description = "Elections", tags = listOf("pool"))
        document(hoaA, "Governing documents", "100% reserve study_v2", description = "Reserve funding")

        http.titles(ownerA, hoaA, "q" to "BYLAWS") shouldBe listOf("Bylaws 2026")
        http.titles(ownerA, hoaA, "q" to "amended") shouldBe listOf("Bylaws 2026")
        http.titles(ownerA, hoaA, "q" to "minutes").shouldContainExactlyInAnyOrder("March board meeting", "Annual meeting")
        http.titles(ownerA, hoaA, "q" to "pool").shouldContainExactlyInAnyOrder("March board meeting", "Annual meeting")
        http.titles(ownerA, hoaA, "q" to "pool  budget") shouldBe listOf("March board meeting")
        // % and _ are literal characters, not wildcards.
        http.titles(ownerA, hoaA, "q" to "100%") shouldBe listOf("100% reserve study_v2")
        http.titles(ownerA, hoaA, "q" to "y_v").shouldContainExactly("100% reserve study_v2")
        http.titles(ownerA, hoaA, "q" to "%").shouldContainExactly("100% reserve study_v2")
        http.titles(ownerA, hoaA, "q" to "nothing matches").shouldBeEmpty()

        val minutes = http.get("/v1/hoas/$hoaA/document-categories") { bearerAuth(ownerA) }
            .body<DocumentCategoriesResponse>().categories.single { it.name == "Minutes" }
        minutes.documentCount shouldBe 2
        http.titles(ownerA, hoaA, "category" to minutes.id, "q" to "meeting", "sort" to "title")
            .shouldContainExactly("Annual meeting", "March board meeting")
    }

    @Test
    fun `sorting and server pagination`() = testApplication {
        val http = client()
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        document(hoaA, "Minutes", "Charlie", date = LocalDate.of(2025, 1, 1))
        document(hoaA, "Minutes", "alpha", date = null)
        document(hoaA, "Minutes", "Bravo", date = LocalDate.of(2026, 1, 1))
        document(hoaA, "Minutes", "delta", date = LocalDate.of(2024, 1, 1))

        http.titles(ownerA, hoaA, "sort" to "title") shouldBe listOf("alpha", "Bravo", "Charlie", "delta")
        http.titles(ownerA, hoaA, "sort" to "title", "order" to "desc") shouldBe listOf("delta", "Charlie", "Bravo", "alpha")
        // Newest upload first by default.
        http.titles(ownerA, hoaA) shouldBe listOf("delta", "Bravo", "alpha", "Charlie")
        http.titles(ownerA, hoaA, "sort" to "uploaded", "order" to "asc") shouldBe listOf("Charlie", "alpha", "Bravo", "delta")
        // Undated documents last in both directions.
        http.titles(ownerA, hoaA, "sort" to "documentDate") shouldBe listOf("delta", "Charlie", "Bravo", "alpha")
        http.titles(ownerA, hoaA, "sort" to "documentDate", "order" to "desc") shouldBe listOf("Bravo", "Charlie", "delta", "alpha")

        val first = http.list(ownerA, hoaA, "sort" to "title", "pageSize" to 3).body<DocumentsPageResponse>()
        first.documents.map { it.title } shouldBe listOf("alpha", "Bravo", "Charlie")
        first.total shouldBe 4
        val second = http.list(ownerA, hoaA, "sort" to "title", "pageSize" to 3, "page" to 2).body<DocumentsPageResponse>()
        second.documents.map { it.title } shouldBe listOf("delta")
        second.page shouldBe 2
        http.titles(ownerA, hoaA, "pageSize" to 3, "page" to 3).shouldBeEmpty()

        listOf(
            "sort" to "size", "order" to "up", "page" to "0", "page" to "x", "pageSize" to "51", "pageSize" to "0",
            "category" to "not-a-uuid", "q" to "x".repeat(201),
        ).forEach { param ->
            val response = http.list(ownerA, hoaA, param)
            response.status shouldBe HttpStatusCode.BadRequest
            response.body<ErrorResponse>().error shouldBe "invalid_query"
        }
    }

    @Test
    fun `a download link is short-lived, named after the title and audited`() = testApplication {
        val http = client()
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        val bylaws = document(hoaA, "Governing documents", "Bylaws 2026: amended")

        val before = Instant.now()
        val response = http.link(ownerA, hoaA, bylaws)
        response.status shouldBe HttpStatusCode.OK
        val link = response.body<DownloadLinkResponse>()
        link.fileName shouldBe "Bylaws 2026_ amended.pdf"
        link.url shouldStartWith "https://storage.test/hoa/$hoaA/documents/$bylaws/"
        link.url shouldContain "expires=300"
        Duration.between(before, Instant.parse(link.expiresAt)).toMinutes() shouldBe 5L

        val event = db.dsl.selectFrom(EVENT).where(EVENT.ACTION.eq("document.download_link_issued")).fetchSingle()
        event.hoaId shouldBe hoaA
        event.targetId shouldBe bylaws
        event.channel shouldBe "api"
        event.actorUserId shouldBe db.dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT)
            .where(USER_ACCOUNT.EMAIL.eq("a@example.test")).fetchSingle(USER_ACCOUNT.ID)
        // The link itself is never recorded.
        event.details.data() shouldContain "versionId"
        event.details.data().contains("storage.test") shouldBe false
    }

    @Test
    fun `owners cannot see or download another HOA's documents`() = testApplication {
        val http = client()
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        http.becomeOwner(ownerB, unitB1, "b@example.test")
        val docA = document(hoaA, "Minutes", "HOA A minutes")
        val docB = document(hoaB, "Minutes", "HOA B minutes")

        http.titles(ownerB, hoaB) shouldBe listOf("HOA B minutes")
        // Another HOA looks exactly like one that does not exist.
        listOf(hoaA, UUID.randomUUID()).forEach { hoa ->
            val response = http.list(ownerB, hoa)
            response.status shouldBe HttpStatusCode.NotFound
            response.body<ErrorResponse>().error shouldBe "not_found"
            http.get("/v1/hoas/$hoa/document-categories") { bearerAuth(ownerB) }.status shouldBe HttpStatusCode.NotFound
        }
        http.get("/v1/hoas/$hoaA/documents/$docA") { bearerAuth(ownerB) }.status shouldBe HttpStatusCode.NotFound
        http.link(ownerB, hoaA, docA).status shouldBe HttpStatusCode.NotFound
        // A document of another HOA cannot be reached through one's own HOA either.
        http.get("/v1/hoas/$hoaB/documents/$docA") { bearerAuth(ownerB) }.status shouldBe HttpStatusCode.NotFound
        http.link(ownerB, hoaB, docA).status shouldBe HttpStatusCode.NotFound
        http.link(ownerA, hoaA, docB).status shouldBe HttpStatusCode.NotFound
        http.get("/v1/hoas/not-a-uuid/documents") { bearerAuth(ownerA) }.status shouldBe HttpStatusCode.NotFound
        http.link(ownerA, hoaA, UUID.randomUUID()).status shouldBe HttpStatusCode.NotFound

        db.dsl.fetchCount(EVENT, EVENT.ACTION.eq("document.download_link_issued")) shouldBe 0
        // Signed in but not a registered user, and not signed in at all.
        http.list(TestAuth.token("uid-x", "x@example.test"), hoaA).status shouldBe HttpStatusCode.Forbidden
        http.get("/v1/hoas/$hoaA/documents").status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `access ends with the last active property in the HOA`() = testApplication {
        val http = client()
        val unitA2 = Fixtures.property(db, hoaA, "Unit 02")
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        http.becomeOwner(ownerA, unitA2, "a@example.test")
        val doc = document(hoaA, "Minutes", "HOA A minutes")
        val userId = db.dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT)
            .where(USER_ACCOUNT.EMAIL.eq("a@example.test")).fetchSingle(USER_ACCOUNT.ID)!!

        db.transaction { Ownerships.end(it, hoaA, unitA1.propertyId, listOf(userId)) }
        // Still owns Unit 02: access continues.
        http.titles(ownerA, hoaA) shouldBe listOf("HOA A minutes")

        db.transaction { Ownerships.end(it, hoaA, unitA2.propertyId, listOf(userId)) }
        http.list(ownerA, hoaA).status shouldBe HttpStatusCode.NotFound
        http.get("/v1/hoas/$hoaA/documents/$doc") { bearerAuth(ownerA) }.status shouldBe HttpStatusCode.NotFound
        http.link(ownerA, hoaA, doc).status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `administrator roles do not grant owner access`() {
        // GA-10: access derives from property ownership only. Root owns no property here.
        ListOwnerDocuments(db)(root, hoaA, DocumentQuery()) shouldBe ListOwnerDocuments.Result.NotFound
    }

    @Test
    fun `without storage configured, download links are unavailable`() = testApplication {
        val http = client(withStorage = false)
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        val doc = document(hoaA, "Minutes", "HOA A minutes")

        http.titles(ownerA, hoaA) shouldBe listOf("HOA A minutes")
        val response = http.link(ownerA, hoaA, doc)
        response.status shouldBe HttpStatusCode.ServiceUnavailable
        response.body<ErrorResponse>().error shouldBe "documents_unavailable"
    }
}
