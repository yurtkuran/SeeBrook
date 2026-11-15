package cc.seebrook.app.http

import cc.seebrook.document.DocumentQuery
import cc.seebrook.document.DocumentSort
import cc.seebrook.document.GetOwnerDocument
import cc.seebrook.document.IssueDownloadLink
import cc.seebrook.document.ListOwnerCategories
import cc.seebrook.document.ListOwnerDocuments
import cc.seebrook.document.OwnerDocumentView
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.storage.ObjectStorage
import cc.seebrook.shared.api.DocumentCategoriesResponse
import cc.seebrook.shared.api.DocumentCategoryResponse
import cc.seebrook.shared.api.DocumentResponse
import cc.seebrook.shared.api.DocumentsPageResponse
import cc.seebrook.shared.api.DownloadLinkResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The owner document actions; null [issueDownloadLink] means storage is not configured. */
class DocumentActions(db: Database, storage: ObjectStorage?) {
    val list = ListOwnerDocuments(db)
    val get = GetOwnerDocument(db)
    val categories = ListOwnerCategories(db)
    val issueDownloadLink = storage?.let { IssueDownloadLink(db, it) }
}

private fun String.toUuid(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private fun OwnerDocumentView.toResponse() = DocumentResponse(
    id = id.toString(),
    title = title,
    description = description,
    categoryId = category.id.toString(),
    categoryName = category.name,
    tags = tags,
    documentDate = documentDate?.toString(),
    uploadedAt = DateTimeFormatter.ISO_INSTANT.format(uploadedAt),
    pageCount = pageCount,
    sizeBytes = sizeBytes,
)

/**
 * Parses `q`, `category`, `sort` (title|uploaded|documentDate), `order` (asc|desc), `page` and
 * `pageSize`. Returns null for any malformed value, so clients get 400 rather than a guess.
 */
internal fun Parameters.toDocumentQuery(): DocumentQuery? {
    fun int(name: String, default: Int): Int? = get(name)?.toIntOrNull() ?: default.takeIf { get(name) == null }
    val sort = when (get("sort") ?: "uploaded") {
        "title" -> DocumentSort.TITLE
        "uploaded" -> DocumentSort.UPLOADED
        "documentDate" -> DocumentSort.DOCUMENT_DATE
        else -> return null
    }
    // Uploaded date defaults to newest first; title and document date to their natural order.
    val descending = when (get("order")) {
        null -> sort == DocumentSort.UPLOADED
        "asc" -> false
        "desc" -> true
        else -> return null
    }
    val categoryId = get("category")?.let { it.toUuid() ?: return null }
    val search = get("q")
    if (search != null && search.length > 200) return null
    return DocumentQuery(
        search = search,
        categoryId = categoryId,
        sort = sort,
        descending = descending,
        page = int("page", 1) ?: return null,
        pageSize = int("pageSize", DocumentQuery.DEFAULT_PAGE_SIZE) ?: return null,
    )
}

/**
 * Owner access to the HOA document library (GA-10). Unknown HOAs, HOAs the user has no active
 * property in, and unpublished or unknown documents all answer 404 `not_found` (GA-06).
 */
fun Route.documentRoutes(accounts: AccountActions, actions: DocumentActions) {
    authenticate(FIREBASE_AUTH) {
        route("/v1/hoas/{hoaId}") {
            get("/document-categories") {
                val actor = call.requireUser(accounts) ?: return@get
                val hoaId = call.parameters["hoaId"]?.toUuid() ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
                val categories = io { actions.categories(actor, hoaId) }
                    ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
                call.respond(
                    DocumentCategoriesResponse(
                        categories.map { DocumentCategoryResponse(it.category.id.toString(), it.category.name, it.documentCount) },
                    ),
                )
            }

            get("/documents") {
                val actor = call.requireUser(accounts) ?: return@get
                val hoaId = call.parameters["hoaId"]?.toUuid() ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
                val query = call.request.queryParameters.toDocumentQuery()
                    ?: return@get call.error(HttpStatusCode.BadRequest, "invalid_query")
                when (val result = io { actions.list(actor, hoaId, query) }) {
                    is ListOwnerDocuments.Result.Listed -> call.respond(
                        DocumentsPageResponse(
                            documents = result.page.documents.map { it.toResponse() },
                            page = result.page.page,
                            pageSize = result.page.pageSize,
                            total = result.page.total,
                        ),
                    )
                    ListOwnerDocuments.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    is ListOwnerDocuments.Result.InvalidQuery -> call.error(HttpStatusCode.BadRequest, "invalid_query")
                }
            }

            get("/documents/{documentId}") {
                val actor = call.requireUser(accounts) ?: return@get
                val hoaId = call.parameters["hoaId"]?.toUuid()
                val documentId = call.parameters["documentId"]?.toUuid()
                val document = if (hoaId != null && documentId != null) io { actions.get(actor, hoaId, documentId) } else null
                document ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
                call.respond(document.toResponse())
            }

            // POST: each call issues a new link and records an audit event.
            post("/documents/{documentId}/download-link") {
                val actor = call.requireUser(accounts) ?: return@post
                val issue = actions.issueDownloadLink
                    ?: return@post call.error(HttpStatusCode.ServiceUnavailable, "documents_unavailable")
                val hoaId = call.parameters["hoaId"]?.toUuid()
                val documentId = call.parameters["documentId"]?.toUuid()
                if (hoaId == null || documentId == null) return@post call.error(HttpStatusCode.NotFound, "not_found")
                when (val result = io { issue(actor, hoaId, documentId) }) {
                    is IssueDownloadLink.Result.Issued -> call.respond(
                        DownloadLinkResponse(result.url.toString(), result.expiresAt.toString(), result.fileName),
                    )
                    IssueDownloadLink.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                }
            }
        }
    }
}
