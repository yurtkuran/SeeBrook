package cc.seebrook.app

import cc.seebrook.platform.config.SmtpConfig
import cc.seebrook.platform.config.SmtpSecurity
import cc.seebrook.platform.email.EmailDeliveryException
import cc.seebrook.platform.email.EmailRejectedException
import cc.seebrook.platform.email.EmailTemplate
import cc.seebrook.platform.email.Recipient
import cc.seebrook.platform.email.RenderedEmail
import cc.seebrook.platform.email.SmtpEmailGateway
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** The SMTP email gateway against a real Mailpit server, the same one used for local development (ED-36). */
class SmtpEmailGatewayTest {
    companion object {
        private val mailpit: GenericContainer<*> by lazy {
            GenericContainer<Nothing>("axllent/mailpit:v1.33.0").apply {
                withExposedPorts(1025, 8025)
                waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025))
                start()
            }
        }
    }

    private val http = HttpClient.newHttpClient()
    private val gateway by lazy {
        SmtpEmailGateway(
            SmtpConfig(mailpit.host, mailpit.getMappedPort(1025), null, null, SmtpSecurity.NONE),
            from = "Seebrook (test) <no-reply@seebrook.test>",
            replyTo = "board@example.test",
        )
    }

    private fun api(path: String, method: String = "GET"): JsonElement? {
        val request = HttpRequest.newBuilder(URI("http://${mailpit.host}:${mailpit.getMappedPort(8025)}/api/v1/$path"))
            .method(method, HttpRequest.BodyPublishers.noBody()).build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return if (method == "GET") Json.parseToJsonElement(body) else null
    }

    private fun messages(): List<JsonObject> =
        requireNotNull(api("messages")).jsonObject.getValue("messages").jsonArray.map { it.jsonObject }

    private fun message(summary: JsonObject) = requireNotNull(api("message/${summary.str("ID")}")).jsonObject
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.toAddresses() = getValue("To").jsonArray.map { it.jsonObject.str("Address") }

    @BeforeEach
    fun clear() {
        api("messages", method = "DELETE")
    }

    @Test
    fun `a transactional email arrives with our sender, reply-to and UTF-8 text`() {
        gateway.sendTransactional(Recipient("owner@example.test", "Zoë Owner"), RenderedEmail("Welcome to Ünit 7", "Hello Zoë"))

        val summary = messages().single()
        summary.toAddresses() shouldBe listOf("owner@example.test")
        summary.str("Subject") shouldBe "Welcome to Ünit 7"
        val full = message(summary)
        full.getValue("From").jsonObject.str("Address") shouldBe "no-reply@seebrook.test"
        full.getValue("ReplyTo").jsonArray.single().jsonObject.str("Address") shouldBe "board@example.test"
        full.str("Text").trim() shouldBe "Hello Zoë"
    }

    @Test
    fun `an email with HTML carries both versions`() {
        gateway.sendTransactional(Recipient("owner@example.test"), RenderedEmail("Notice", "Plain body", "<p>Rich body</p>"))

        val full = message(messages().single())
        full.str("Text") shouldContain "Plain body"
        full.str("HTML") shouldContain "<p>Rich body</p>"
    }

    @Test
    fun `bulk sends one personal message per recipient and skips bad addresses`() {
        val template = EmailTemplate { r -> RenderedEmail("Annual meeting", "Dear ${r.name ?: "owner"}, the meeting is on Monday.") }
        val recipients = listOf(
            Recipient("a@example.test", "Ann"),
            Recipient("not an address", "Broken"),
            Recipient("b@example.test", "Bo"),
        )

        val result = gateway.sendBulk(UUID.randomUUID(), recipients, template)

        result.sent shouldBe 2
        result.failed shouldBe listOf(Recipient("not an address", "Broken"))
        val received = messages()
        received.map { it.toAddresses() } shouldContainExactlyInAnyOrder listOf(listOf("a@example.test"), listOf("b@example.test"))
        val toAnn = message(received.first { it.toAddresses() == listOf("a@example.test") })
        toAnn.str("Text") shouldContain "Dear Ann"
        toAnn.str("Text") shouldNotContain "Bo"
        val headers = requireNotNull(api("message/${toAnn.str("ID")}/headers")).jsonObject
        headers.getValue("Precedence").jsonArray.single().jsonPrimitive.content shouldBe "bulk"
    }

    @Test
    fun `a malformed recipient is a permanent rejection`() {
        shouldThrow<EmailRejectedException> { gateway.sendTransactional(Recipient("not an address"), RenderedEmail("s", "t")) }
    }

    @Test
    fun `an unreachable server is a delivery failure the caller can retry`() {
        val down = SmtpEmailGateway(SmtpConfig("127.0.0.1", 1, null, null, SmtpSecurity.NONE), "no-reply@seebrook.test")
        val failure = shouldThrow<EmailDeliveryException> { down.sendTransactional(Recipient("owner@example.test"), RenderedEmail("s", "t")) }
        failure.message shouldNotContain "owner@example.test"
        (failure is EmailRejectedException) shouldBe false
    }
}
