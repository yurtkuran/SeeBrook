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
import cc.seebrook.shared.api.HoaLogoResponse
import cc.seebrook.shared.api.PropertiesResponse
import cc.seebrook.tenant.LogoImage
import cc.seebrook.tenant.RemoveHoaLogo
import cc.seebrook.tenant.SetHoaLogo
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
 * HOA logos (ED-55): HOA administrators set them; owners see the version with their property and
 * fetch the logo through a short-lived link, only for HOAs where they own a property.
 */
class HoaLogoTest {
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

    private suspend fun HttpClient.logoVersion(token: String): String? =
        get("/v1/me/properties") { bearerAuth(token) }.body<PropertiesResponse>().properties.single().hoaLogoVersion

    @Test
    fun `owners get the logo version with the property and a short-lived link to the PNG`() = testApplication {
        val http = client()
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        http.logoVersion(ownerA) shouldBe null
        http.get("/v1/properties/${unitA1.propertyId}/hoa-logo") { bearerAuth(ownerA) }.apply {
            status shouldBe HttpStatusCode.NotFound
            body<ErrorResponse>().error shouldBe "no_logo"
        }

        SetHoaLogo(db, storage)(root, hoaA, image(1024, 512, "jpeg")).shouldBeInstanceOf<SetHoaLogo.Result.Set>()

        val version = http.logoVersion(ownerA)
        version shouldNotBe null
        val link = http.get("/v1/properties/${unitA1.propertyId}/hoa-logo") { bearerAuth(ownerA) }.body<HoaLogoResponse>()
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
        SetHoaLogo(db, storage)(root, hoaB, image(64, 64)).shouldBeInstanceOf<SetHoaLogo.Result.Set>()
        http.get("/v1/properties/${unitB1.propertyId}/hoa-logo") { bearerAuth(ownerA) }.apply {
            status shouldBe HttpStatusCode.NotFound
            body<ErrorResponse>().error shouldBe "not_found"
        }
    }

    @Test
    fun `replacing gives a new version and deletes the old file, removing clears it`() = testApplication {
        val http = client()
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        SetHoaLogo(db, storage)(root, hoaA, image(64, 64))
        val first = http.logoVersion(ownerA)
        SetHoaLogo(db, storage)(root, hoaA, image(128, 64))
        val second = http.logoVersion(ownerA)
        second shouldNotBe first
        storage.objects.keys.toList() shouldContainExactly listOf("hoa/$hoaA/logo/$second.png")

        RemoveHoaLogo(db, storage)(root, hoaA) shouldBe RemoveHoaLogo.Result.Removed
        http.logoVersion(ownerA) shouldBe null
        storage.objects.keys.shouldBeEmpty()
        RemoveHoaLogo(db, storage)(root, hoaA) shouldBe RemoveHoaLogo.Result.NoLogo

        db.dsl.select(EVENT.ACTION).from(EVENT).where(EVENT.HOA_ID.eq(hoaA)).and(EVENT.ACTION.like("hoa.logo%"))
            .orderBy(EVENT.ID).fetch(EVENT.ACTION) shouldBe listOf("hoa.logo_set", "hoa.logo_set", "hoa.logo_removed")
    }

    @Test
    fun `only HOA administrators of that HOA and Root may set the logo`() {
        val hoaAdmin = root.copy(roles = setOf(RoleGrant(Role.HOA_ADMIN, hoaA)))
        val documentAdmin = root.copy(roles = setOf(RoleGrant(Role.DOCUMENT_ADMIN, hoaA)))
        SetHoaLogo(db, storage)(documentAdmin, hoaA, image(64, 64)) shouldBe SetHoaLogo.Result.Forbidden
        SetHoaLogo(db, storage)(hoaAdmin, hoaB, image(64, 64)) shouldBe SetHoaLogo.Result.Forbidden
        RemoveHoaLogo(db, storage)(documentAdmin, hoaA) shouldBe RemoveHoaLogo.Result.Forbidden
        storage.objects.keys.shouldBeEmpty()
        SetHoaLogo(db, storage)(hoaAdmin, hoaA, image(64, 64)).shouldBeInstanceOf<SetHoaLogo.Result.Set>()
        storage.objects.keys shouldHaveSize 1
    }

    @Test
    fun `files that are not usable images are refused and nothing is stored`() {
        val set = SetHoaLogo(db, storage)
        fun reason(content: ByteArray) = (set(root, hoaA, content) as SetHoaLogo.Result.InvalidImage).reason
        reason(ByteArray(0)) shouldContain "empty"
        reason("%PDF-1.7 not an image".toByteArray()) shouldContain "not a PNG or JPEG"
        reason(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)) shouldContain "could not be read"
        reason(image(16, 16)) shouldContain "smaller than 32"
        reason(image(5000, 40)) shouldContain "larger than 4096"
        reason(ByteArray(LogoImage.MAX_UPLOAD_BYTES + 1)) shouldContain "larger than 2 MB"
        storage.objects.keys.shouldBeEmpty()
    }

    @Test
    fun `without storage the logo link answers 503`() = testApplication {
        val http = client(withStorage = false)
        http.becomeOwner(ownerA, unitA1, "a@example.test")
        http.get("/v1/properties/${unitA1.propertyId}/hoa-logo") { bearerAuth(ownerA) }.apply {
            status shouldBe HttpStatusCode.ServiceUnavailable
            body<ErrorResponse>().error shouldBe "logo_unavailable"
        }
    }
}
