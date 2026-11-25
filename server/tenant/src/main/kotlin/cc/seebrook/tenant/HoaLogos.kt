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
import javax.imageio.ImageIO

/**
 * Turns an uploaded logo into the PNG the apps show (ED-55). Only PNG and JPEG are read; the
 * image's size is checked before it is decoded, and the result is drawn again into a new PNG at
 * most [MAX_SIDE] pixels on its longest side, so nothing from the uploaded file but its pixels is kept.
 */
object LogoImage {
    const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024
    const val MAX_SIDE = 512
    private const val MAX_SOURCE_SIDE = 4096
    private const val MIN_SIDE = 32

    sealed interface Result {
        class Valid(val png: ByteArray, val width: Int, val height: Int) : Result
        data class Invalid(val reason: String) : Result
    }

    init {
        // Server and command line only: never open a window or a Dock icon.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
    }

    fun prepare(content: ByteArray): Result {
        if (content.isEmpty()) return Result.Invalid("the file is empty")
        if (content.size > MAX_UPLOAD_BYTES) return Result.Invalid("the file is larger than 2 MB")
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
                if (width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE) {
                    return Result.Invalid("the image is larger than $MAX_SOURCE_SIDE × $MAX_SOURCE_SIDE pixels")
                }
                if (width < MIN_SIDE || height < MIN_SIDE) {
                    return Result.Invalid("the image is smaller than $MIN_SIDE × $MIN_SIDE pixels")
                }
                reader.read(0)
            } catch (e: Exception) {
                return Result.Invalid("the image could not be read")
            } finally {
                reader.dispose()
            }
        }
        val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, Math.round(source.width * scale).toInt())
        val height = maxOf(1, Math.round(source.height * scale).toInt())
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = out.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
        return Result.Valid(png, width, height)
    }

    private fun ByteArray.startsWith(vararg bytes: Int) =
        size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }
}

internal object LogoKeys {
    fun new(hoaId: UUID) = "hoa/$hoaId/logo/${UUID.randomUUID()}.png"

    /** The version the apps cache a logo by: its file name, which is new for every logo. */
    fun version(key: String) = key.substringAfterLast('/').removeSuffix(".png")
}

/** Sets an HOA's logo (ED-55). HOA administrators and Root may. */
class SetHoaLogo(private val db: Database, private val storage: ObjectStorage, private val clock: Clock = Clock.systemUTC()) {
    sealed interface Result {
        data class Set(val width: Int, val height: Int, val bytes: Int) : Result
        data class InvalidImage(val reason: String) : Result
        data object HoaNotFound : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, content: ByteArray): Result {
        if (!actor.can(Permission.MANAGE_HOA_PROFILE, hoaId)) return Result.Forbidden
        val image = when (val prepared = LogoImage.prepare(content)) {
            is LogoImage.Result.Invalid -> return Result.InvalidImage(prepared.reason)
            is LogoImage.Result.Valid -> prepared
        }
        // Store first: if the transaction below fails, the new file is left unreferenced, which is harmless.
        val key = LogoKeys.new(hoaId)
        storage.put(key, image.png, "image/png")
        val previous = db.transaction { tx ->
            val row = tx.select(HOA.LOGO_KEY).from(HOA).where(HOA.ID.eq(hoaId)).forUpdate().fetchOne()
                ?: return@transaction null
            tx.update(HOA)
                .set(HOA.LOGO_KEY, key)
                .set(HOA.LOGO_UPDATED_AT, OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC))
                .where(HOA.ID.eq(hoaId))
                .execute()
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "hoa.logo_set",
                    hoaId = hoaId,
                    targetType = "hoa",
                    targetId = hoaId,
                    details = buildJsonObject {
                        put("width", JsonPrimitive(image.width))
                        put("height", JsonPrimitive(image.height))
                        put("bytes", JsonPrimitive(image.png.size))
                        put("replaced", JsonPrimitive(row.value1() != null))
                    },
                ),
            )
            Previous(row.value1())
        } ?: return Result.HoaNotFound.also { runCatching { storage.delete(key) } }
        // The old file is no longer referenced; if deleting it fails it is merely left behind.
        previous.key?.let { runCatching { storage.delete(it) } }
        return Result.Set(image.width, image.height, image.png.size)
    }

    private data class Previous(val key: String?)
}

/** Removes an HOA's logo (ED-55); the apps then show their placeholder. */
class RemoveHoaLogo(private val db: Database, private val storage: ObjectStorage) {
    sealed interface Result {
        data object Removed : Result
        data object NoLogo : Result
        data object HoaNotFound : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID): Result {
        if (!actor.can(Permission.MANAGE_HOA_PROFILE, hoaId)) return Result.Forbidden
        // The transaction answers the result and, when removed, the file to delete afterwards.
        val (result, key) = db.transaction { tx ->
            val row = tx.select(HOA.LOGO_KEY).from(HOA).where(HOA.ID.eq(hoaId)).forUpdate().fetchOne()
                ?: return@transaction Result.HoaNotFound to null
            val key = row.value1() ?: return@transaction Result.NoLogo to null
            tx.update(HOA)
                .setNull(HOA.LOGO_KEY)
                .setNull(HOA.LOGO_UPDATED_AT)
                .where(HOA.ID.eq(hoaId))
                .execute()
            Audit.record(tx, actor, AuditEntry(action = "hoa.logo_removed", hoaId = hoaId, targetType = "hoa", targetId = hoaId))
            Result.Removed to key
        }
        key?.let { runCatching { storage.delete(it) } }
        return result
    }
}

/**
 * A short-lived link to an HOA's logo, for owners of a property in that HOA; the caller checks
 * that (ED-55). Not audited: the logo is shown to every owner and holds nothing personal.
 */
class HoaLogoLink(private val db: Database, private val storage: ObjectStorage, private val clock: Clock = Clock.systemUTC()) {
    data class Link(val url: URI, val expiresAt: Instant, val version: String)

    operator fun invoke(hoaId: UUID): Link? {
        val key = db.dsl.select(HOA.LOGO_KEY).from(HOA).where(HOA.ID.eq(hoaId)).fetchOne()?.value1() ?: return null
        val validFor = Duration.ofMinutes(5)
        return Link(storage.signedDownloadUrl(key, validFor, "logo.png"), Instant.now(clock).plus(validFor), LogoKeys.version(key))
    }
}
