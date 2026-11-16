package cc.seebrook.document

import cc.seebrook.db.generated.document.Tables.CATEGORY
import cc.seebrook.db.generated.document.Tables.VERSION
import cc.seebrook.db.generated.document.tables.records.DocumentRecord
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.storage.ObjectStorage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import cc.seebrook.db.generated.document.Tables.DOCUMENT_ as DOCUMENT

/*
 * Administrator changes to existing documents and categories (GA-11, GA-13). All need "manage
 * documents" in the HOA, are HOA-scoped (another HOA's IDs answer NotFound), audited, and never
 * delete a document, a version or a stored file: history stays restorable.
 */

private const val MAX_REASON = 1000

/** A trimmed reason of 1 to 1000 characters, or null when missing or too long. */
private fun reasonOf(reason: String?): String? = reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_REASON }

/** The document, locked until the transaction ends so concurrent changes to it queue up. */
private fun lockDocument(tx: DSLContext, hoaId: UUID, documentId: UUID): DocumentRecord? =
    tx.selectFrom(DOCUMENT).where(DOCUMENT.ID.eq(documentId)).and(DOCUMENT.HOA_ID.eq(hoaId)).forUpdate().fetchOne()

/** True while an upload or replacement of the document is waiting for its scan. */
private fun hasPendingVersion(tx: DSLContext, hoaId: UUID, documentId: UUID): Boolean =
    tx.fetchExists(
        VERSION,
        VERSION.HOA_ID.eq(hoaId).and(VERSION.DOCUMENT_ID.eq(documentId)).and(VERSION.STATE.eq(VersionState.QUARANTINED)),
    )

private fun audit(tx: DSLContext, actor: UserActor, action: String, hoaId: UUID, type: String, id: UUID, reason: String?, details: JsonObject) =
    Audit.record(tx, actor, AuditEntry(action, hoaId, type, id, reason, details))

/**
 * Uploads a new PDF for an existing document (GA-11). The new version goes through the same
 * checks, quarantine and scan as an upload (ED-38); owners keep seeing the current version until
 * the new one is clean, then it becomes current. If it is rejected, the current version stays.
 * One replacement at a time; an archived document must be restored first.
 */
class ReplaceDocument(private val db: Database, private val storage: ObjectStorage, private val maxBytes: Int) {
    sealed interface Result {
        data class Uploaded(val versionId: UUID, val pageCount: Int) : Result
        data class InvalidFile(val reason: String) : Result
        data object NotFound : Result
        data object Archived : Result
        data object AlreadyProcessing : Result
        data object Forbidden : Result
    }

    private fun blocked(tx: DSLContext, hoaId: UUID, documentId: UUID, lock: Boolean): Result? {
        val document = if (lock) {
            lockDocument(tx, hoaId, documentId)
        } else {
            tx.selectFrom(DOCUMENT).where(DOCUMENT.ID.eq(documentId)).and(DOCUMENT.HOA_ID.eq(hoaId)).fetchOne()
        } ?: return Result.NotFound
        return when {
            document.status == DocumentStatus.ARCHIVED -> Result.Archived
            hasPendingVersion(tx, hoaId, documentId) -> Result.AlreadyProcessing
            else -> null
        }
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, documentId: UUID, fileName: String, content: ByteArray): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        // Checked before storing so the usual refusals leave nothing behind; checked again under lock below.
        db.transaction { blocked(it, hoaId, documentId, lock = false) }?.let { return it }
        val pageCount = when (val check = PdfValidator.check(content, maxBytes)) {
            is PdfValidator.Result.Invalid -> return Result.InvalidFile(check.reason)
            is PdfValidator.Result.Valid -> check.pageCount
        }
        val key = StorageKeys.quarantine(hoaId)
        storage.put(key, content, "application/pdf")

        return db.transaction { tx ->
            blocked(tx, hoaId, documentId, lock = true)?.let { return@transaction it }
            val checksum = sha256(content)
            val versionId = requireNotNull(
                tx.insertInto(VERSION)
                    .set(VERSION.HOA_ID, hoaId)
                    .set(VERSION.DOCUMENT_ID, documentId)
                    .set(VERSION.OBJECT_KEY, key)
                    .set(VERSION.FILE_NAME, fileName.take(255))
                    .set(VERSION.SIZE_BYTES, content.size.toLong())
                    .set(VERSION.SHA256, checksum)
                    .set(VERSION.PAGE_COUNT, pageCount)
                    .set(VERSION.UPLOADED_BY, actor.userId)
                    .returning(VERSION.ID).fetchSingle().id,
            )
            // A document whose first upload was rejected gets another chance.
            tx.update(DOCUMENT).set(DOCUMENT.STATUS, DocumentStatus.PROCESSING)
                .where(DOCUMENT.ID.eq(documentId)).and(DOCUMENT.STATUS.eq(DocumentStatus.REJECTED)).execute()
            DocumentVersionJob.enqueue(tx, hoaId, versionId)
            audit(
                tx, actor, "document.replacement_uploaded", hoaId, "document", documentId, null,
                buildJsonObject {
                    put("versionId", versionId.toString())
                    put("fileName", fileName.take(255))
                    put("sizeBytes", content.size)
                    put("sha256", checksum)
                },
            )
            Result.Uploaded(versionId, pageCount)
        }
    }
}

/** Hides a published document from owners; it is kept and can be restored (requirements: retained indefinitely). */
class ArchiveDocument(private val db: Database) {
    sealed interface Result {
        data object Archived : Result
        data class NotPublished(val status: String) : Result
        data object NotFound : Result
        data object ReasonRequired : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, documentId: UUID, reason: String?): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        val why = reasonOf(reason) ?: return Result.ReasonRequired
        return db.transaction { tx ->
            val document = lockDocument(tx, hoaId, documentId) ?: return@transaction Result.NotFound
            if (document.status != DocumentStatus.PUBLISHED) return@transaction Result.NotPublished(document.status)
            tx.update(DOCUMENT)
                .set(DOCUMENT.STATUS, DocumentStatus.ARCHIVED)
                .set(DOCUMENT.ARCHIVED_AT, DSL.currentOffsetDateTime())
                .where(DOCUMENT.ID.eq(documentId)).execute()
            audit(tx, actor, "document.archived", hoaId, "document", documentId, why, buildJsonObject { put("title", document.title) })
            Result.Archived
        }
    }
}

/** Makes an archived document visible to owners again, with its current version. */
class RestoreDocument(private val db: Database) {
    sealed interface Result {
        data object Restored : Result
        data class NotArchived(val status: String) : Result
        data object NotFound : Result
        data object ReasonRequired : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, documentId: UUID, reason: String?): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        val why = reasonOf(reason) ?: return Result.ReasonRequired
        return db.transaction { tx ->
            val document = lockDocument(tx, hoaId, documentId) ?: return@transaction Result.NotFound
            if (document.status != DocumentStatus.ARCHIVED) return@transaction Result.NotArchived(document.status)
            tx.update(DOCUMENT)
                .set(DOCUMENT.STATUS, DocumentStatus.PUBLISHED)
                .setNull(DOCUMENT.ARCHIVED_AT)
                .where(DOCUMENT.ID.eq(documentId)).execute()
            audit(tx, actor, "document.restored", hoaId, "document", documentId, why, buildJsonObject { put("title", document.title) })
            Result.Restored
        }
    }
}

/**
 * Rolls a document back to an earlier clean version (GA-11). The newer versions are kept, so the
 * rollback can itself be undone. Refused while a replacement is being scanned, which would
 * otherwise override the rollback when it finishes.
 */
class RestoreVersion(private val db: Database) {
    sealed interface Result {
        data object Restored : Result
        data object AlreadyCurrent : Result
        data object VersionNotFound : Result
        data class VersionNotClean(val state: String) : Result
        data class NotPublished(val status: String) : Result
        data object AlreadyProcessing : Result
        data object NotFound : Result
        data object ReasonRequired : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, documentId: UUID, versionId: UUID, reason: String?): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        val why = reasonOf(reason) ?: return Result.ReasonRequired
        return db.transaction { tx ->
            val document = lockDocument(tx, hoaId, documentId) ?: return@transaction Result.NotFound
            if (document.status !in setOf(DocumentStatus.PUBLISHED, DocumentStatus.ARCHIVED)) {
                return@transaction Result.NotPublished(document.status)
            }
            val version = tx.selectFrom(VERSION)
                .where(VERSION.ID.eq(versionId)).and(VERSION.DOCUMENT_ID.eq(documentId)).and(VERSION.HOA_ID.eq(hoaId))
                .fetchOne() ?: return@transaction Result.VersionNotFound
            if (version.state != VersionState.CLEAN) return@transaction Result.VersionNotClean(version.state)
            if (document.currentVersionId == versionId) return@transaction Result.AlreadyCurrent
            if (hasPendingVersion(tx, hoaId, documentId)) return@transaction Result.AlreadyProcessing
            tx.update(DOCUMENT).set(DOCUMENT.CURRENT_VERSION_ID, versionId).where(DOCUMENT.ID.eq(documentId)).execute()
            audit(
                tx, actor, "document.version_restored", hoaId, "document", documentId, why,
                buildJsonObject {
                    put("fromVersionId", document.currentVersionId.toString())
                    put("toVersionId", versionId.toString())
                },
            )
            Result.Restored
        }
    }
}

/** Every version of a document, newest first, for administrators choosing a rollback. */
class ListVersions(private val db: Database) {
    data class Row(
        val versionId: UUID,
        val state: String,
        val isCurrent: Boolean,
        val uploadedAt: OffsetDateTime,
        val fileName: String,
        val pageCount: Int,
        val sizeBytes: Long,
        val rejectionReason: String?,
    )

    sealed interface Result {
        data class Listed(val title: String, val status: String, val versions: List<Row>) : Result
        data object NotFound : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, documentId: UUID): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        return db.transaction { tx ->
            val document = tx.selectFrom(DOCUMENT).where(DOCUMENT.ID.eq(documentId)).and(DOCUMENT.HOA_ID.eq(hoaId)).fetchOne()
                ?: return@transaction Result.NotFound
            val versions = tx.selectFrom(VERSION)
                .where(VERSION.DOCUMENT_ID.eq(documentId)).and(VERSION.HOA_ID.eq(hoaId))
                .orderBy(VERSION.UPLOADED_AT.desc(), VERSION.ID.desc())
                .fetch { v ->
                    val id = requireNotNull(v.id)
                    Row(id, v.state, id == document.currentVersionId, v.uploadedAt, v.fileName, v.pageCount, v.sizeBytes, v.rejectionReason)
                }
            Result.Listed(document.title, document.status, versions)
        }
    }
}

/** Changes to a document's details; null fields stay as they are. */
data class DetailChanges(
    val title: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    /** Set a date, or clear it with [clearDocumentDate]. */
    val documentDate: LocalDate? = null,
    val clearDocumentDate: Boolean = false,
    /** Moves the document to another category of the same HOA (GA-13 reassign). */
    val categoryName: String? = null,
) {
    val isEmpty get() = this == DetailChanges()
}

/** Edits a document's details or moves it to another category; the PDF is unchanged. Audits before and after. */
class UpdateDocumentDetails(private val db: Database) {
    sealed interface Result {
        data object Updated : Result
        data object NoChange : Result
        data class InvalidDetails(val problems: List<Pair<String, String>>) : Result
        data object CategoryNotFound : Result
        data object NotFound : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, documentId: UUID, changes: DetailChanges): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        if (changes.isEmpty) return Result.NoChange
        return db.transaction { tx ->
            val document = lockDocument(tx, hoaId, documentId) ?: return@transaction Result.NotFound
            val before = DocumentDetails(document.title, document.description, document.tags.filterNotNull(), document.documentDate)
            val after = DocumentDetails(
                title = changes.title ?: before.title,
                description = changes.description ?: before.description,
                tags = changes.tags ?: before.tags,
                documentDate = if (changes.clearDocumentDate) null else changes.documentDate ?: before.documentDate,
            ).normalized()
            after.problems().takeIf { it.isNotEmpty() }?.let { return@transaction Result.InvalidDetails(it) }
            val categoryBefore = tx.select(CATEGORY.NAME).from(CATEGORY).where(CATEGORY.ID.eq(document.categoryId)).fetchSingle(CATEGORY.NAME)!!
            val category = changes.categoryName?.let { CategoryLookup.byName(tx, hoaId, it) ?: return@transaction Result.CategoryNotFound }
                ?: CategoryView(requireNotNull(document.categoryId), categoryBefore)
            if (after == before && category.id == document.categoryId) return@transaction Result.NoChange

            tx.update(DOCUMENT)
                .set(DOCUMENT.TITLE, after.title)
                .set(DOCUMENT.DESCRIPTION, after.description)
                .set(DOCUMENT.TAGS, after.tags.toTypedArray())
                .set(DOCUMENT.DOCUMENT_DATE, after.documentDate)
                .set(DOCUMENT.CATEGORY_ID, category.id)
                .where(DOCUMENT.ID.eq(documentId)).execute()
            fun snapshot(d: DocumentDetails, categoryName: String) = buildJsonObject {
                put("title", d.title)
                put("description", d.description)
                put("tags", JsonArray(d.tags.map(::JsonPrimitive)))
                put("documentDate", d.documentDate?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("category", categoryName)
            }
            audit(
                tx, actor, "document.details_updated", hoaId, "document", documentId, null,
                buildJsonObject {
                    put("before", snapshot(before.normalized(), categoryBefore))
                    put("after", snapshot(after, category.name))
                },
            )
            Result.Updated
        }
    }
}

/** Renames a category; its ID, and so every document in it, is unaffected (GA-13). */
class RenameCategory(private val db: Database) {
    sealed interface Result {
        data object Renamed : Result
        data object NotFound : Result
        data object AlreadyExists : Result
        data object InvalidName : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, name: String, newName: String): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        val clean = CategoryNames.clean(newName) ?: return Result.InvalidName
        return db.transaction { tx ->
            val category = CategoryLookup.byName(tx, hoaId, name) ?: return@transaction Result.NotFound
            // Changing only the case of its own name is allowed.
            CategoryLookup.byName(tx, hoaId, clean)?.takeIf { it.id != category.id }?.let { return@transaction Result.AlreadyExists }
            tx.update(CATEGORY).set(CATEGORY.NAME, clean).where(CATEGORY.ID.eq(category.id)).execute()
            audit(
                tx, actor, "document.category_renamed", hoaId, "category", category.id, null,
                buildJsonObject {
                    put("from", category.name)
                    put("to", clean)
                },
            )
            Result.Renamed
        }
    }
}

/** Moves every document of one category, in every status, to another; typically to empty it before deletion. */
class ReassignDocuments(private val db: Database) {
    sealed interface Result {
        data class Moved(val count: Int) : Result
        data object SameCategory : Result
        data class CategoryNotFound(val name: String) : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, fromName: String, toName: String): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        return db.transaction { tx ->
            val from = CategoryLookup.byName(tx, hoaId, fromName) ?: return@transaction Result.CategoryNotFound(fromName)
            val to = CategoryLookup.byName(tx, hoaId, toName) ?: return@transaction Result.CategoryNotFound(toName)
            if (from.id == to.id) return@transaction Result.SameCategory
            val moved = tx.update(DOCUMENT).set(DOCUMENT.CATEGORY_ID, to.id)
                .where(DOCUMENT.HOA_ID.eq(hoaId)).and(DOCUMENT.CATEGORY_ID.eq(from.id))
                .returning(DOCUMENT.ID).fetch().map { requireNotNull(it.id).toString() }
            audit(
                tx, actor, "document.category_reassigned", hoaId, "category", from.id, null,
                buildJsonObject {
                    put("from", from.name)
                    put("to", to.name)
                    put("toCategoryId", to.id.toString())
                    put("documentIds", JsonArray(moved.map(::JsonPrimitive)))
                },
            )
            Result.Moved(moved.size)
        }
    }
}

/**
 * Deletes a category only when no document of any status — archived, rejected or processing
 * included — is in it (GA-13). The category row is removed; the audit trail keeps the record.
 */
class DeleteCategory(private val db: Database) {
    sealed interface Result {
        data object Deleted : Result
        data class NotEmpty(val documents: Int) : Result
        data object NotFound : Result
        data object ReasonRequired : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, name: String, reason: String?): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        val why = reasonOf(reason) ?: return Result.ReasonRequired
        return db.transaction { tx ->
            val category = CategoryLookup.byName(tx, hoaId, name) ?: return@transaction Result.NotFound
            // Lock the category so a concurrent upload or move into it waits, then count.
            tx.selectFrom(CATEGORY).where(CATEGORY.ID.eq(category.id)).forUpdate().fetchSingle()
            val count = tx.fetchCount(DOCUMENT, DOCUMENT.HOA_ID.eq(hoaId).and(DOCUMENT.CATEGORY_ID.eq(category.id)))
            if (count > 0) return@transaction Result.NotEmpty(count)
            tx.deleteFrom(CATEGORY).where(CATEGORY.ID.eq(category.id)).and(CATEGORY.HOA_ID.eq(hoaId)).execute()
            audit(tx, actor, "document.category_deleted", hoaId, "category", category.id, why, buildJsonObject { put("name", category.name) })
            Result.Deleted
        }
    }
}
