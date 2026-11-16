package cc.seebrook.document

import cc.seebrook.db.generated.document.Tables.CATEGORY
import cc.seebrook.db.generated.document.Tables.VERSION
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Channel
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.jobs.Job
import cc.seebrook.platform.jobs.JobHandler
import cc.seebrook.platform.jobs.Jobs
import cc.seebrook.platform.scan.MalwareScanner
import cc.seebrook.platform.scan.ScanResult
import cc.seebrook.platform.storage.ObjectStorage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import cc.seebrook.db.generated.document.Tables.DOCUMENT_ as DOCUMENT

internal object DocumentStatus {
    const val PROCESSING = "processing"
    const val PUBLISHED = "published"
    const val ARCHIVED = "archived"
    const val REJECTED = "rejected"
}

internal object VersionState {
    const val QUARANTINED = "quarantined"
    const val CLEAN = "clean"
    const val REJECTED = "rejected"
}

/** Where files live in private storage. Quarantined uploads are kept apart from published files. */
internal object StorageKeys {
    fun quarantine(hoaId: UUID) = "hoa/$hoaId/quarantine/${UUID.randomUUID()}.pdf"
    fun published(hoaId: UUID, documentId: UUID, versionId: UUID) = "hoa/$hoaId/documents/$documentId/$versionId.pdf"
}

/** Administrator-supplied document details (GA-13 requirements: title, description, category required). */
data class DocumentDetails(
    val title: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val documentDate: LocalDate? = null,
) {
    fun normalized() = copy(
        title = title.trim(),
        description = description.trim(),
        tags = tags.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
    )

    /** Field problems as `field to message`; empty when valid. Expects [normalized] values. */
    fun problems(): List<Pair<String, String>> = buildList {
        if (title.isEmpty()) add("title" to "is required")
        if (title.length > 200) add("title" to "is longer than 200 characters")
        if (description.isEmpty()) add("description" to "is required")
        if (description.length > 2000) add("description" to "is longer than 2000 characters")
        if (tags.size > 20) add("tags" to "has more than 20 tags")
        tags.firstOrNull { it.length > 50 }?.let { add("tags" to "'${it.take(20)}…' is longer than 50 characters") }
    }
}

/** The job that checks, scans and publishes one uploaded version (ED-38). Payload: the version ID only. */
object DocumentVersionJob {
    const val KIND = "document.process_version"

    internal fun enqueue(tx: DSLContext, hoaId: UUID, versionId: UUID) =
        Jobs.enqueue(tx, KIND, hoaId, "document-version:$versionId", buildJsonObject { put("versionId", versionId.toString()) })
}

internal fun sha256(content: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))

/**
 * Uploads a new document (GA-12, ED-38). The file is checked at once (so the operator gets an
 * immediate answer), stored in quarantine, and queued for the malware scan; it is published only
 * when the scan is clean. Nothing about it is visible to owners until then.
 */
class UploadDocument(private val db: Database, private val storage: ObjectStorage, private val maxBytes: Int) {
    sealed interface Result {
        data class Uploaded(val documentId: UUID, val versionId: UUID, val pageCount: Int) : Result
        data class InvalidDetails(val problems: List<Pair<String, String>>) : Result
        data class InvalidFile(val reason: String) : Result
        data object CategoryNotFound : Result
        data object Forbidden : Result
    }

    operator fun invoke(
        actor: UserActor,
        hoaId: UUID,
        categoryName: String,
        details: DocumentDetails,
        fileName: String,
        content: ByteArray,
    ): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        val clean = details.normalized()
        clean.problems().takeIf { it.isNotEmpty() }?.let { return Result.InvalidDetails(it) }
        val category = db.transaction { CategoryLookup.byName(it, hoaId, categoryName) } ?: return Result.CategoryNotFound
        val pageCount = when (val check = PdfValidator.check(content, maxBytes)) {
            is PdfValidator.Result.Invalid -> return Result.InvalidFile(check.reason)
            is PdfValidator.Result.Valid -> check.pageCount
        }

        // Store first: if this fails nothing is recorded. If the transaction below fails, the
        // quarantined file is left unreferenced, which is harmless.
        val key = StorageKeys.quarantine(hoaId)
        storage.put(key, content, "application/pdf")

        return db.transaction { tx ->
            val documentId = requireNotNull(
                tx.insertInto(DOCUMENT)
                    .set(DOCUMENT.HOA_ID, hoaId)
                    .set(DOCUMENT.CATEGORY_ID, category.id)
                    .set(DOCUMENT.TITLE, clean.title)
                    .set(DOCUMENT.DESCRIPTION, clean.description)
                    .set(DOCUMENT.TAGS, clean.tags.toTypedArray())
                    .set(DOCUMENT.DOCUMENT_DATE, clean.documentDate)
                    .returning(DOCUMENT.ID).fetchSingle().id,
            )
            val versionId = requireNotNull(
                tx.insertInto(VERSION)
                    .set(VERSION.HOA_ID, hoaId)
                    .set(VERSION.DOCUMENT_ID, documentId)
                    .set(VERSION.OBJECT_KEY, key)
                    .set(VERSION.FILE_NAME, fileName.take(255))
                    .set(VERSION.SIZE_BYTES, content.size.toLong())
                    .set(VERSION.SHA256, sha256(content))
                    .set(VERSION.PAGE_COUNT, pageCount)
                    .set(VERSION.UPLOADED_BY, actor.userId)
                    .returning(VERSION.ID).fetchSingle().id,
            )
            DocumentVersionJob.enqueue(tx, hoaId, versionId)
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "document.uploaded",
                    hoaId = hoaId,
                    targetType = "document",
                    targetId = documentId,
                    details = buildJsonObject {
                        put("versionId", versionId.toString())
                        put("title", clean.title)
                        put("category", category.name)
                        put("fileName", fileName.take(255))
                        put("sizeBytes", content.size)
                        put("sha256", sha256(content))
                    },
                ),
            )
            Result.Uploaded(documentId, versionId, pageCount)
        }
    }
}

/**
 * Job handler: re-checks a quarantined version, scans it and publishes it if clean (GA-12).
 * Infected or invalid files are rejected and removed from storage; their record is kept. If the
 * scanner or storage is unavailable the job is retried and the file stays quarantined.
 */
class ProcessDocumentVersion(
    private val db: Database,
    private val storage: ObjectStorage,
    private val scanner: MalwareScanner,
    private val maxBytes: Int,
) : JobHandler {
    private val log = LoggerFactory.getLogger(ProcessDocumentVersion::class.java)

    private data class Pending(val id: UUID, val hoaId: UUID, val documentId: UUID, val key: String, val sha256: String)

    override fun handle(job: Job) {
        val versionId = UUID.fromString(requireNotNull(job.payload["versionId"]).jsonPrimitive.content)
        val pending = db.transaction { tx ->
            tx.selectFrom(VERSION).where(VERSION.ID.eq(versionId)).fetchOne()
                ?.takeIf { it.state == VersionState.QUARANTINED }
                ?.let { Pending(versionId, requireNotNull(it.hoaId), requireNotNull(it.documentId), it.objectKey, it.sha256) }
        } ?: return

        val content = storage.get(pending.key) ?: return reject(pending, "the uploaded file is missing from storage", null)
        if (sha256(content) != pending.sha256) return reject(pending, "the stored file does not match the upload", null)
        (PdfValidator.check(content, maxBytes) as? PdfValidator.Result.Invalid)?.let { return reject(pending, it.reason, null) }

        when (val result = scanner.scan(content)) {
            is ScanResult.Infected -> reject(pending, "malware detected (${result.signature.take(100)})", result.engine)
            is ScanResult.Clean -> publish(pending, result.engine)
        }
    }

    private fun publish(pending: Pending, engine: String) {
        val key = StorageKeys.published(pending.hoaId, pending.documentId, pending.id)
        // Copy, commit, then remove the quarantined copy: a crash at any point leaves a state the
        // retried job completes.
        storage.copy(pending.key, key)
        db.transaction { tx ->
            tx.update(VERSION)
                .set(VERSION.STATE, VersionState.CLEAN)
                .set(VERSION.OBJECT_KEY, key)
                .set(VERSION.SCANNED_BY, engine.take(200))
                .set(VERSION.SCANNED_AT, DSL.currentOffsetDateTime())
                .where(VERSION.ID.eq(pending.id)).and(VERSION.STATE.eq(VersionState.QUARANTINED))
                .execute()
            // A first upload publishes the document; a replacement (GA-11) becomes its current
            // version, also while archived. One pending version at a time (ReplaceDocument).
            val published = tx.update(DOCUMENT)
                .set(DOCUMENT.STATUS, DocumentStatus.PUBLISHED)
                .set(DOCUMENT.CURRENT_VERSION_ID, pending.id)
                .set(DOCUMENT.PUBLISHED_AT, DSL.currentOffsetDateTime())
                .where(DOCUMENT.ID.eq(pending.documentId)).and(DOCUMENT.STATUS.eq(DocumentStatus.PROCESSING))
                .execute()
            val replaced = if (published == 1) {
                0
            } else {
                tx.update(DOCUMENT)
                    .set(DOCUMENT.CURRENT_VERSION_ID, pending.id)
                    .where(DOCUMENT.ID.eq(pending.documentId))
                    .and(DOCUMENT.STATUS.`in`(DocumentStatus.PUBLISHED, DocumentStatus.ARCHIVED))
                    .execute()
            }
            Audit.record(
                tx, null, Channel.SYSTEM,
                AuditEntry(
                    action = "document.version_published",
                    hoaId = pending.hoaId,
                    targetType = "document",
                    targetId = pending.documentId,
                    details = buildJsonObject {
                        put("versionId", pending.id.toString())
                        put("scannedBy", engine.take(200))
                        put("documentPublished", published == 1)
                        put("replacedCurrentVersion", replaced == 1)
                    },
                ),
            )
        }
        storage.delete(pending.key)
    }

    private fun reject(pending: Pending, reason: String, engine: String?) {
        db.transaction { tx ->
            tx.update(VERSION)
                .set(VERSION.STATE, VersionState.REJECTED)
                .set(VERSION.REJECTION_REASON, reason.take(200))
                .set(VERSION.SCANNED_BY, engine?.take(200))
                .set(VERSION.SCANNED_AT, engine?.let { DSL.currentOffsetDateTime() })
                .where(VERSION.ID.eq(pending.id)).and(VERSION.STATE.eq(VersionState.QUARANTINED))
                .execute()
            tx.update(DOCUMENT)
                .set(DOCUMENT.STATUS, DocumentStatus.REJECTED)
                .where(DOCUMENT.ID.eq(pending.documentId)).and(DOCUMENT.STATUS.eq(DocumentStatus.PROCESSING))
                .execute()
            Audit.record(
                tx, null, Channel.SYSTEM,
                AuditEntry(
                    action = "document.version_rejected",
                    hoaId = pending.hoaId,
                    targetType = "document",
                    targetId = pending.documentId,
                    reason = reason.take(200),
                    details = buildJsonObject {
                        put("versionId", pending.id.toString())
                        engine?.let { put("scannedBy", it.take(200)) }
                    },
                ),
            )
        }
        // A rejected (possibly malicious) file is never kept.
        storage.delete(pending.key)
        log.warn("Document version {} rejected", pending.id)
    }
}

/** Every document of the HOA with its state, for document administrators. */
class ListDocumentsForAdmin(private val db: Database) {
    data class Row(
        val documentId: UUID,
        val title: String,
        val category: String,
        val status: String,
        val tags: List<String>,
        val documentDate: LocalDate?,
        val latestVersionState: String?,
        val rejectionReason: String?,
    )

    sealed interface Result {
        data class Listed(val rows: List<Row>) : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        return db.transaction { tx ->
            val latest = tx.select(VERSION.DOCUMENT_ID, VERSION.STATE, VERSION.REJECTION_REASON)
                .distinctOn(VERSION.DOCUMENT_ID)
                .from(VERSION).where(VERSION.HOA_ID.eq(hoaId))
                .orderBy(VERSION.DOCUMENT_ID, VERSION.UPLOADED_AT.desc(), VERSION.ID.desc())
                .fetch().associate { requireNotNull(it.value1()) to (it.value2() to it.value3()) }
            val rows = tx.select(DOCUMENT.ID, DOCUMENT.TITLE, CATEGORY.NAME, DOCUMENT.STATUS, DOCUMENT.TAGS, DOCUMENT.DOCUMENT_DATE)
                .from(DOCUMENT).join(CATEGORY).on(CATEGORY.ID.eq(DOCUMENT.CATEGORY_ID))
                .where(DOCUMENT.HOA_ID.eq(hoaId))
                .orderBy(DSL.lower(CATEGORY.NAME), DSL.lower(DOCUMENT.TITLE))
                .fetch { r ->
                    val id = requireNotNull(r.value1())
                    Row(id, r.value2(), r.value3(), r.value4(), r.value5().toList(), r.value6(), latest[id]?.first, latest[id]?.second)
                }
            Result.Listed(rows)
        }
    }
}
