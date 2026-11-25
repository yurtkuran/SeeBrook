package cc.seebrook.app.http

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.MemoryStorage
import cc.seebrook.app.support.TestAuth
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.RoleGrant
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.shared.api.AcceptInvitationRequest
import cc.seebrook.shared.api.ErrorResponse
import cc.seebrook.shared.api.HoaImageResponse
import cc.seebrook.shared.api.PropertiesResponse
import cc.seebrook.tenant.HoaImageKind
import cc.seebrook.tenant.RemoveHoaImage
import cc.seebrook.tenant.SetHoaImage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * HOA logos (ED-55) and community photos (ED-57): HOA administrators set them; owners see the
 * version with their property and fetch the image through a short-lived link, only for HOAs where
 * they own a property.
 */
class HoaImageTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val storage = MemoryStorage()
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val hoaA = Fixtures.hoa(db, "hoa-a")
    private val hoaB = Fixtures.hoa(db, "hoa-b")
    private val unitA1 = Fixtures.property(db, hoaA, "Unit 01")
    private val unitB1 = Fixtures.property(db, hoaB, "Unit 01")
    private val ownerA = TestAuth.token("uid-a", "a@example.test")

    @AfterEach
    fun close() = db.close()

    private fun image(width: Int, height: Int, format: String = "png"): ByteArray {
        val type = if (format == "png") BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        image.createGraphics().apply { color = Color(0x1E40AF); fillRect(0, 0, width / 2, height); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
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

    private val setLogo = SetHoaImage(db, storage, HoaImageKind.LOGO)
    private val removeLogo = RemoveHoaImage(db, storage, HoaImageKind.LOGO)
    private val setPhoto = SetHoaImage(db, storage, HoaImageKind.PHOTO)

    private suspend fun HttpClient.logoVersion(token: String): String? =
        get("/v1/me/properties") { bearerAuth(token) }.body<PropertiesResponse>().properties.single().hoaLogoVersion

    private suspend fun HttpClient.photoVersion(token: String): String? =
        get("/v1/me/properties") { bearerAuth(token) }.body<PropertiesResponse>().properties.single().hoaPhotoVersion

    @Test
    fun `owners get the logo version with the property and a short-lived link to the PNG`() = testApplication {
        val http = client()
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        http.logoVersion(ownerA) shouldBe null
        http.get("/v1/properties/${unitA1.propertyId}/hoa-logo") { bearerAuth(ownerA) }.apply {
            status shouldBe HttpStatusCode.NotFound
            body<ErrorResponse>().error shouldBe "no_logo"
        }

        setLogo(root, hoaA, image(1024, 512, "jpeg")).shouldBeInstanceOf<SetHoaImage.Result.Set>()

        val version = http.logoVersion(ownerA)
        version shouldNotBe null
        val link = http.get("/v1/properties/${unitA1.propertyId}/hoa-logo") { bearerAuth(ownerA) }.body<HoaImageResponse>()
        link.version shouldBe version
        link.url shouldContain "hoa/$hoaA/logo/$version.png"
        // Stored as a PNG no larger than 512 pixels a side, whatever was uploaded.
        val stored = storage.objects.getValue("hoa/$hoaA/logo/$version.png")
        val decoded = ImageIO.read(ByteArrayInputStream(stored))
        (decoded.width to decoded.height) shouldBe (512 to 256)
        stored.take(4) shouldBe listOf(0x89, 0x50, 0x4E, 0x47).map { it.toByte() }
    }

    @Test
    fun `a property in another HOA or someone else's property is not found`() = testApplication {
        val http = client()
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        setLogo(root, hoaB, image(64, 64)).shouldBeInstanceOf<SetHoaImage.Result.Set>()
        http.get("/v1/properties/${unitB1.propertyId}/hoa-logo") { bearerAuth(ownerA) }.apply {
            status shouldBe HttpStatusCode.NotFound
            body<ErrorResponse>().error shouldBe "not_found"
        }
    }

    @Test
    fun `replacing gives a new version and deletes the old file, removing clears it`() = testApplication {
        val http = client()
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        setLogo(root, hoaA, image(64, 64))
        val first = http.logoVersion(ownerA)
        setLogo(root, hoaA, image(128, 64))
        val second = http.logoVersion(ownerA)
        second shouldNotBe first
        storage.objects.keys.toList() shouldContainExactly listOf("hoa/$hoaA/logo/$second.png")

        removeLogo(root, hoaA) shouldBe RemoveHoaImage.Result.Removed
        http.logoVersion(ownerA) shouldBe null
        storage.objects.keys.shouldBeEmpty()
        removeLogo(root, hoaA) shouldBe RemoveHoaImage.Result.NoImage

        db.dsl.select(EVENT.ACTION).from(EVENT).where(EVENT.HOA_ID.eq(hoaA)).and(EVENT.ACTION.like("hoa.logo%"))
            .orderBy(EVENT.ID).fetch(EVENT.ACTION) shouldBe listOf("hoa.logo_set", "hoa.logo_set", "hoa.logo_removed")
    }

    @Test
    fun `only HOA administrators of that HOA and Root may set the logo`() {
        val hoaAdmin = root.copy(roles = setOf(RoleGrant(Role.HOA_ADMIN, hoaA)))
        val documentAdmin = root.copy(roles = setOf(RoleGrant(Role.DOCUMENT_ADMIN, hoaA)))
        setLogo(documentAdmin, hoaA, image(64, 64)) shouldBe SetHoaImage.Result.Forbidden
        setLogo(hoaAdmin, hoaB, image(64, 64)) shouldBe SetHoaImage.Result.Forbidden
        removeLogo(documentAdmin, hoaA) shouldBe RemoveHoaImage.Result.Forbidden
        setPhoto(documentAdmin, hoaA, image(640, 480)) shouldBe SetHoaImage.Result.Forbidden
        RemoveHoaImage(db, storage, HoaImageKind.PHOTO)(documentAdmin, hoaA) shouldBe RemoveHoaImage.Result.Forbidden
        storage.objects.keys.shouldBeEmpty()
        setLogo(hoaAdmin, hoaA, image(64, 64)).shouldBeInstanceOf<SetHoaImage.Result.Set>()
        setPhoto(hoaAdmin, hoaA, image(640, 480)).shouldBeInstanceOf<SetHoaImage.Result.Set>()
        storage.objects.keys shouldHaveSize 2
    }

    @Test
    fun `files that are not usable images are refused and nothing is stored`() {
        fun reason(content: ByteArray) = (setLogo(root, hoaA, content) as SetHoaImage.Result.InvalidImage).reason
        reason(ByteArray(0)) shouldContain "empty"
        reason("%PDF-1.7 not an image".toByteArray()) shouldContain "not a PNG or JPEG"
        reason(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)) shouldContain "could not be read"
        reason(image(16, 16)) shouldContain "smaller than 32"
        reason(image(5000, 40)) shouldContain "larger than 4096"
        reason(ByteArray(HoaImageKind.LOGO.maxUploadBytes + 1)) shouldContain "larger than 2 MB"
        fun photoReason(content: ByteArray) = (setPhoto(root, hoaA, content) as SetHoaImage.Result.InvalidImage).reason
        photoReason(image(300, 600)) shouldContain "smaller than 320"
        photoReason(ByteArray(HoaImageKind.PHOTO.maxUploadBytes + 1)) shouldContain "larger than 10 MB"
        storage.objects.keys.shouldBeEmpty()
    }

    @Test
    fun `without storage the logo and photo links answer 503`() = testApplication {
        val http = client(withStorage = false)
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        http.get("/v1/properties/${unitA1.propertyId}/hoa-logo") { bearerAuth(ownerA) }.apply {
            status shouldBe HttpStatusCode.ServiceUnavailable
            body<ErrorResponse>().error shouldBe "logo_unavailable"
        }
        http.get("/v1/properties/${unitA1.propertyId}/hoa-photo") { bearerAuth(ownerA) }.apply {
            status shouldBe HttpStatusCode.ServiceUnavailable
            body<ErrorResponse>().error shouldBe "photo_unavailable"
        }
    }

    @Test
    fun `owners get the photo as a JPEG at most 1600 pixels, separate from the logo`() = testApplication {
        val http = client()
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        http.photoVersion(ownerA) shouldBe null
        http.get("/v1/properties/${unitA1.propertyId}/hoa-photo") { bearerAuth(ownerA) }.apply {
            status shouldBe HttpStatusCode.NotFound
            body<ErrorResponse>().error shouldBe "no_photo"
        }

        // A transparent PNG: stored as a JPEG, transparent parts white, scaled to 1600 wide.
        setPhoto(root, hoaA, image(4000, 3000)).shouldBeInstanceOf<SetHoaImage.Result.Set>()
        http.logoVersion(ownerA) shouldBe null
        val version = http.photoVersion(ownerA)
        version shouldNotBe null
        val link = http.get("/v1/properties/${unitA1.propertyId}/hoa-photo") { bearerAuth(ownerA) }.body<HoaImageResponse>()
        link.version shouldBe version
        link.url shouldContain "hoa/$hoaA/photo/$version.jpg"
        val stored = storage.objects.getValue("hoa/$hoaA/photo/$version.jpg")
        stored.take(3) shouldBe listOf(0xFF, 0xD8, 0xFF).map { it.toByte() }
        val decoded = ImageIO.read(ByteArrayInputStream(stored))
        (decoded.width to decoded.height) shouldBe (1600 to 1200)
        Color(decoded.getRGB(1500, 600)).let { (it.red > 240 && it.green > 240 && it.blue > 240) shouldBe true }

        // Another HOA's photo is not reachable through this owner's property, and someone else's property is not found.
        setPhoto(root, hoaB, image(640, 480))
        http.get("/v1/properties/${unitB1.propertyId}/hoa-photo") { bearerAuth(ownerA) }.status shouldBe HttpStatusCode.NotFound

        RemoveHoaImage(db, storage, HoaImageKind.PHOTO)(root, hoaA) shouldBe RemoveHoaImage.Result.Removed
        http.photoVersion(ownerA) shouldBe null
        db.dsl.select(EVENT.ACTION).from(EVENT).where(EVENT.HOA_ID.eq(hoaA)).and(EVENT.ACTION.like("hoa.photo%"))
            .orderBy(EVENT.ID).fetch(EVENT.ACTION) shouldBe listOf("hoa.photo_set", "hoa.photo_removed")
    }
}
