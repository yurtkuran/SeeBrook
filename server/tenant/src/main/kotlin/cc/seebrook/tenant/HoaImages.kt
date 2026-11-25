package cc.seebrook.tenant

import cc.seebrook.db.generated.tenant.Tables.HOA
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.storage.ObjectStorage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jooq.Field
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The images an HOA shows owners in the app: its logo (ED-55) and its community photo on Home
 * (ED-57). Each kind has its own limits and stored format; everything else is shared.
 */
enum class HoaImageKind(
    /** Used in object keys, API paths, error codes and audit actions: `hoa.<code>_set`. */
    val code: String,
    val label: String,
    val maxUploadBytes: Int,
    val maxSourceSide: Int,
    val minSide: Int,
    /** The stored image is at most this many pixels on its longest side. */
    val maxSide: Int,
    /** PNG keeps a logo's transparency; a photo is stored as a much smaller JPEG. */
    val png: Boolean,
    internal val keyField: Field<String?>,
    internal val updatedAtField: Field<OffsetDateTime?>,
) {
    LOGO("logo", "Logo", 2 * 1024 * 1024, 4096, 32, 512, png = true, HOA.LOGO_KEY, HOA.LOGO_UPDATED_AT),
    PHOTO("photo", "Photo", 10 * 1024 * 1024, 8192, 320, 1600, png = false, HOA.PHOTO_KEY, HOA.PHOTO_UPDATED_AT),
    ;

    val extension: String get() = if (png) "png" else "jpg"
    val contentType: String get() = if (png) "image/png" else "image/jpeg"
    val maxUploadMegabytes: Int get() = maxUploadBytes / (1024 * 1024)
}

/**
 * Turns an uploaded image into the file the apps show. Only PNG and JPEG are read; the image's size
 * is checked before it is decoded, and the result is drawn again into a new file at most
 * [HoaImageKind.maxSide] pixels on its longest side, so nothing from the uploaded file but its
 * pixels is kept.
 */
object HoaImageFile {
    sealed interface Result {
        class Valid(val bytes: ByteArray, val width: Int, val height: Int) : Result
        data class Invalid(val reason: String) : Result
    }

    init {
        // Server and command line only: never open a window or a Dock icon.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
    }

    fun prepare(content: ByteArray, kind: HoaImageKind): Result {
        if (content.isEmpty()) return Result.Invalid("the file is empty")
        if (content.size > kind.maxUploadBytes) return Result.Invalid("the file is larger than ${kind.maxUploadMegabytes} MB")
        val format = when {
            content.startsWith(0x89, 0x50, 0x4E, 0x47) -> "png"
            content.startsWith(0xFF, 0xD8, 0xFF) -> "jpeg"
            else -> return Result.Invalid("the file is not a PNG or JPEG image")
        }
        val source = ImageIO.createImageInputStream(ByteArrayInputStream(content)).use { input ->
            val reader = ImageIO.getImageReadersByFormatName(format).asSequence().firstOrNull()
                ?: return Result.Invalid("the file is not a PNG or JPEG image")
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width > kind.maxSourceSide || height > kind.maxSourceSide) {
                    return Result.Invalid("the image is larger than ${kind.maxSourceSide} × ${kind.maxSourceSide} pixels")
                }
                if (width < kind.minSide || height < kind.minSide) {
                    return Result.Invalid("the image is smaller than ${kind.minSide} × ${kind.minSide} pixels")
                }
                // Large photos are read at a fraction of their size (still at least twice the stored
                // size), which keeps memory low; the final resize below smooths the result.
                val step = maxOf(1, maxOf(width, height) / (kind.maxSide * 2))
                val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                reader.read(0, param)
            } catch (e: Exception) {
                return Result.Invalid("the image could not be read")
            } finally {
                reader.dispose()
            }
        }
        val scale = minOf(1.0, kind.maxSide.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, Math.round(source.width * scale).toInt())
        val height = maxOf(1, Math.round(source.height * scale).toInt())
        val out = BufferedImage(width, height, if (kind.png) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val graphics = out.createGraphics()
        try {
            if (!kind.png) {
                // JPEG has no transparency: transparent parts of a PNG become white.
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return Result.Valid(if (kind.png) png(out) else jpeg(out), width, height)
    }

    private fun png(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    private fun jpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.85f
                }
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(vararg bytes: Int) =
        size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }
}

internal object HoaImageKeys {
    fun new(hoaId: UUID, kind: HoaImageKind) = "hoa/$hoaId/${kind.code}/${UUID.randomUUID()}.${kind.extension}"

    /** The version the apps cache an image by: its file name, which is new for every image. */
    fun version(key: String) = key.substringAfterLast('/').substringBeforeLast('.')
}

/** Sets an HOA's logo or photo. HOA administrators and Root may (ED-55). */
class SetHoaImage(
    private val db: Database,
    private val storage: ObjectStorage,
    private val kind: HoaImageKind,
    private val clock: Clock = Clock.systemUTC(),
) {
    sealed interface Result {
        data class Set(val width: Int, val height: Int, val bytes: Int) : Result
        data class InvalidImage(val reason: String) : Result
        data object HoaNotFound : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, content: ByteArray): Result {
        if (!actor.can(Permission.MANAGE_HOA_PROFILE, hoaId)) return Result.Forbidden
        val image = when (val prepared = HoaImageFile.prepare(content, kind)) {
            is HoaImageFile.Result.Invalid -> return Result.InvalidImage(prepared.reason)
            is HoaImageFile.Result.Valid -> prepared
        }
        // Store first: if the transaction below fails, the new file is left unreferenced, which is harmless.
        val key = HoaImageKeys.new(hoaId, kind)
        storage.put(key, image.bytes, kind.contentType)
        val previous = db.transaction { tx ->
            val row = tx.select(kind.keyField).from(HOA).where(HOA.ID.eq(hoaId)).forUpdate().fetchOne()
                ?: return@transaction null
            tx.update(HOA)
                .set(kind.keyField, key)
                .set(kind.updatedAtField, OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC))
                .where(HOA.ID.eq(hoaId))
                .execute()
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "hoa.${kind.code}_set",
                    hoaId = hoaId,
                    targetType = "hoa",
                    targetId = hoaId,
                    details = buildJsonObject {
                        put("width", JsonPrimitive(image.width))
                        put("height", JsonPrimitive(image.height))
                        put("bytes", JsonPrimitive(image.bytes.size))
                        put("replaced", JsonPrimitive(row.value1() != null))
                    },
                ),
            )
            Previous(row.value1())
        } ?: return Result.HoaNotFound.also { runCatching { storage.delete(key) } }
        // The old file is no longer referenced; if deleting it fails it is merely left behind.
        previous.key?.let { runCatching { storage.delete(it) } }
        return Result.Set(image.width, image.height, image.bytes.size)
    }

    private data class Previous(val key: String?)
}

/** Removes an HOA's logo or photo; the apps then show their placeholder or nothing (ED-55, ED-57). */
class RemoveHoaImage(private val db: Database, private val storage: ObjectStorage, private val kind: HoaImageKind) {
    sealed interface Result {
        data object Removed : Result
        data object NoImage : Result
        data object HoaNotFound : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID): Result {
        if (!actor.can(Permission.MANAGE_HOA_PROFILE, hoaId)) return Result.Forbidden
        // The transaction answers the result and, when removed, the file to delete afterwards.
        val (result, key) = db.transaction { tx ->
            val row = tx.select(kind.keyField).from(HOA).where(HOA.ID.eq(hoaId)).forUpdate().fetchOne()
                ?: return@transaction Result.HoaNotFound to null
            val key = row.value1() ?: return@transaction Result.NoImage to null
            tx.update(HOA)
                .setNull(kind.keyField)
                .setNull(kind.updatedAtField)
                .where(HOA.ID.eq(hoaId))
                .execute()
            Audit.record(tx, actor, AuditEntry(action = "hoa.${kind.code}_removed", hoaId = hoaId, targetType = "hoa", targetId = hoaId))
            Result.Removed to key
        }
        key?.let { runCatching { storage.delete(it) } }
        return result
    }
}

/**
 * A short-lived link to an HOA's logo or photo, for owners of a property in that HOA; the caller
 * checks that (ED-55). Not audited: the images are shown to every owner and hold nothing personal.
 */
class HoaImageLink(private val db: Database, private val storage: ObjectStorage, private val clock: Clock = Clock.systemUTC()) {
    data class Link(val url: URI, val expiresAt: Instant, val version: String)

    operator fun invoke(hoaId: UUID, kind: HoaImageKind): Link? {
        val key = db.dsl.select(kind.keyField).from(HOA).where(HOA.ID.eq(hoaId)).fetchOne()?.value1() ?: return null
        val validFor = Duration.ofMinutes(5)
        return Link(storage.signedDownloadUrl(key, validFor, "${kind.code}.${kind.extension}"), Instant.now(clock).plus(validFor), HoaImageKeys.version(key))
    }
}
