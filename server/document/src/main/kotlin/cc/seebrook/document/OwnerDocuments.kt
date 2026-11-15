package cc.seebrook.document

import cc.seebrook.db.generated.document.Tables.CATEGORY
import cc.seebrook.db.generated.document.Tables.VERSION
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.storage.ObjectStorage
import cc.seebrook.property.Ownerships
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.SortField
import org.jooq.impl.DSL
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import cc.seebrook.db.generated.document.Tables.DOCUMENT_ as DOCUMENT

/** A published document as an owner sees it: only its current, clean version (GA-11). */
data class OwnerDocumentView(
    val id: UUID,
    val title: String,
    val description: String,
    val category: CategoryView,
    val tags: List<String>,
    val documentDate: LocalDate?,
    /** When the current version was uploaded: the requirement's automatic upload date. */
    val uploadedAt: OffsetDateTime,
    val pageCount: Int,
    val sizeBytes: Long,
)

data class CategoryCount(val category: CategoryView, val documentCount: Int)

enum class DocumentSort { TITLE, UPLOADED, DOCUMENT_DATE }

/**
 * Owner browsing options. Search matches title, description, category and tags — never the PDF
 * text; every word must match somewhere (requirements, Document Library).
 */
data class DocumentQuery(
    val search: String? = null,
    val categoryId: UUID? = null,
    val sort: DocumentSort = DocumentSort.UPLOADED,
    val descending: Boolean = true,
    val page: Int = 1,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    /** Search words, lower-cased; at most [MAX_TERMS] words, each cut to [MAX_TERM_LENGTH]. */
    internal fun terms(): List<String> =
        search.orEmpty().trim().lowercase().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }.distinct().take(MAX_TERMS).map { it.take(MAX_TERM_LENGTH) }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
        const val MAX_PAGE = 10_000
        private const val MAX_TERMS = 8
        private const val MAX_TERM_LENGTH = 50
    }
}

data class DocumentPage(val documents: List<OwnerDocumentView>, val page: Int, val pageSize: Int, val total: Int)

/**
 * Owner-side reads of the document library. Access is HOA-wide and derives only from at least
 * one active property in that HOA (GA-10); roles grant nothing here. Without access, every
 * lookup answers "not found", so an owner cannot tell another HOA's documents exist (GA-06).
 */
internal object OwnerLibrary {
    fun hasAccess(tx: DSLContext, actor: UserActor, hoaId: UUID) = Ownerships.hasActivePropertyIn(tx, hoaId, actor.userId)

    /** Published documents whose current version is clean, with their category; HOA-scoped joins. */
    fun visible(tx: DSLContext, hoaId: UUID, extra: Condition = DSL.noCondition()) =
        tx.select(
            DOCUMENT.ID, DOCUMENT.TITLE, DOCUMENT.DESCRIPTION, DOCUMENT.TAGS, DOCUMENT.DOCUMENT_DATE,
            CATEGORY.ID, CATEGORY.NAME, VERSION.ID, VERSION.UPLOADED_AT, VERSION.PAGE_COUNT, VERSION.SIZE_BYTES,
            VERSION.OBJECT_KEY,
        )
            .from(DOCUMENT)
            .join(CATEGORY).on(CATEGORY.ID.eq(DOCUMENT.CATEGORY_ID)).and(CATEGORY.HOA_ID.eq(DOCUMENT.HOA_ID))
            .join(VERSION).on(VERSION.ID.eq(DOCUMENT.CURRENT_VERSION_ID)).and(VERSION.HOA_ID.eq(DOCUMENT.HOA_ID))
            .where(DOCUMENT.HOA_ID.eq(hoaId))
            .and(DOCUMENT.STATUS.eq(DocumentStatus.PUBLISHED))
            .and(VERSION.STATE.eq(VersionState.CLEAN))
            .and(extra)

    fun view(r: Record) = OwnerDocumentView(
        id = requireNotNull(r.get(DOCUMENT.ID)),
        title = r.get(DOCUMENT.TITLE),
        description = r.get(DOCUMENT.DESCRIPTION),
        category = CategoryView(requireNotNull(r.get(CATEGORY.ID)), r.get(CATEGORY.NAME)),
        tags = r.get(DOCUMENT.TAGS).filterNotNull(),
        documentDate = r.get(DOCUMENT.DOCUMENT_DATE),
        uploadedAt = r.get(VERSION.UPLOADED_AT),
        pageCount = r.get(VERSION.PAGE_COUNT),
        sizeBytes = r.get(VERSION.SIZE_BYTES),
    )
}

/** `%`, `_` and `\` in search words match themselves, not as wildcards. */
private fun containsPattern(term: String) =
    "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

private val TAGS_TEXT: Field<String> = DSL.field("array_to_string({0}, ' ')", String::class.java, DOCUMENT.TAGS)

private fun matches(term: String): Condition {
    val pattern = containsPattern(term)
    return DSL.or(
        DOCUMENT.TITLE.likeIgnoreCase(pattern, '\\'),
        DOCUMENT.DESCRIPTION.likeIgnoreCase(pattern, '\\'),
        CATEGORY.NAME.likeIgnoreCase(pattern, '\\'),
        TAGS_TEXT.likeIgnoreCase(pattern, '\\'),
    )
}

private fun order(query: DocumentQuery): List<SortField<*>> {
    fun <T> Field<T>.dir(): SortField<T> = if (query.descending) desc() else asc()
    val primary = when (query.sort) {
        DocumentSort.TITLE -> listOf(DSL.lower(DOCUMENT.TITLE).dir())
        DocumentSort.UPLOADED -> listOf(VERSION.UPLOADED_AT.dir())
        // Undated documents always come last, whichever the direction.
        DocumentSort.DOCUMENT_DATE -> listOf(DOCUMENT.DOCUMENT_DATE.dir().nullsLast(), DSL.lower(DOCUMENT.TITLE).asc())
    }
    // A stable tie-breaker keeps pages from overlapping or skipping rows.
    return primary + DOCUMENT.ID.asc()
}

/** Lists, searches, sorts and pages the HOA's published documents for an owner (server pagination). */
class ListOwnerDocuments(private val db: Database) {
    sealed interface Result {
        data class Listed(val page: DocumentPage) : Result
        data object NotFound : Result
        data class InvalidQuery(val reason: String) : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, query: DocumentQuery): Result {
        if (query.page !in 1..DocumentQuery.MAX_PAGE) return Result.InvalidQuery("page must be between 1 and ${DocumentQuery.MAX_PAGE}")
        if (query.pageSize !in 1..DocumentQuery.MAX_PAGE_SIZE) {
            return Result.InvalidQuery("pageSize must be between 1 and ${DocumentQuery.MAX_PAGE_SIZE}")
        }
        return db.transaction { tx ->
            if (!OwnerLibrary.hasAccess(tx, actor, hoaId)) return@transaction Result.NotFound
            val filter = DSL.and(
                query.terms().map(::matches) +
                    listOfNotNull(query.categoryId?.let { CATEGORY.ID.eq(it) }),
            )
            val total = tx.fetchCount(OwnerLibrary.visible(tx, hoaId, filter))
            val documents = OwnerLibrary.visible(tx, hoaId, filter)
                .orderBy(order(query))
                .limit(query.pageSize)
                .offset((query.page - 1) * query.pageSize)
                .fetch(OwnerLibrary::view)
            Result.Listed(DocumentPage(documents, query.page, query.pageSize, total))
        }
    }
}

/** One published document for an owner, or null when it does not exist or they may not see it. */
class GetOwnerDocument(private val db: Database) {
    operator fun invoke(actor: UserActor, hoaId: UUID, documentId: UUID): OwnerDocumentView? =
        db.transaction { tx ->
            if (!OwnerLibrary.hasAccess(tx, actor, hoaId)) return@transaction null
            OwnerLibrary.visible(tx, hoaId, DOCUMENT.ID.eq(documentId)).fetchOne(OwnerLibrary::view)
        }
}

/** Categories that hold at least one published document, for the owner's filter; null without access. */
class ListOwnerCategories(private val db: Database) {
    operator fun invoke(actor: UserActor, hoaId: UUID): List<CategoryCount>? =
        db.transaction { tx ->
            if (!OwnerLibrary.hasAccess(tx, actor, hoaId)) return@transaction null
            OwnerLibrary.visible(tx, hoaId).fetch(OwnerLibrary::view)
                .groupBy { it.category }
                .map { (category, docs) -> CategoryCount(category, docs.size) }
                .sortedBy { it.category.name.lowercase() }
        }
}

/**
 * Issues a short-lived signed link to the current version of a published document (requirements:
 * private storage, short-lived signed URLs). Each issue is audited, as viewing or downloading a
 * PDF always needs one; the storage provider's own access to the link is not observable here.
 */
class IssueDownloadLink(
    private val db: Database,
    private val storage: ObjectStorage,
    private val validFor: Duration = DEFAULT_VALIDITY,
) {
    sealed interface Result {
        data class Issued(val url: URI, val expiresAt: Instant, val fileName: String) : Result
        data object NotFound : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, documentId: UUID): Result =
        db.transaction { tx ->
            if (!OwnerLibrary.hasAccess(tx, actor, hoaId)) return@transaction Result.NotFound
            val row = OwnerLibrary.visible(tx, hoaId, DOCUMENT.ID.eq(documentId)).fetchOne()
                ?: return@transaction Result.NotFound
            val fileName = downloadName(row.get(DOCUMENT.TITLE))
            val expiresAt = Instant.now().plus(validFor)
            // Signing is local (no network call); the link is only returned once the audit commits.
            val url = storage.signedDownloadUrl(row.get(VERSION.OBJECT_KEY), validFor, fileName)
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "document.download_link_issued",
                    hoaId = hoaId,
                    targetType = "document",
                    targetId = documentId,
                    details = buildJsonObject {
                        put("versionId", requireNotNull(row.get(VERSION.ID)).toString())
                        put("validForSeconds", validFor.seconds)
                    },
                ),
            )
            Result.Issued(url, expiresAt, fileName)
        }

    companion object {
        val DEFAULT_VALIDITY: Duration = Duration.ofMinutes(5)

        /** The saved file is named after the title, not the administrator's original file name. */
        internal fun downloadName(title: String): String =
            title.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(100).ifBlank { "document" } + ".pdf"
    }
}
