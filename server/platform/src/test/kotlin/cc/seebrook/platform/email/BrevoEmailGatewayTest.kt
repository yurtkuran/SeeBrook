package cc.seebrook.platform.email

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID

/** The Brevo HTTP adapter against a local fake of Brevo's API (ED-36); no real account needed. */
class BrevoEmailGatewayTest {
    private data class Call(val path: String, val apiKey: String?, val body: JsonObject)

    private val calls = mutableListOf<Call>()

    /** Decides the fake's reply (status, body) for each request. */
    private var reply: (JsonObject) -> Pair<Int, String> = { 201 to """{"messageId":"<m1@smtp-relay.mailin.fr>"}""" }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            synchronized(calls) { calls += Call(exchange.requestURI.path, exchange.requestHeaders.getFirst("api-key"), body) }
            val (status, response) = reply(body)
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val gateway = BrevoEmailGateway(
        apiKey = "test-api-key",
        from = "Seebrook <no-reply@seebrook.test>",
        replyTo = "board@example.test",
        baseUrl = "http://127.0.0.1:${server.address.port}/v3",
    )

    @AfterEach
    fun stop() = server.stop(0)

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `a transactional email is one API call with our sender, content and an escaped HTML copy`() {
        gateway.sendTransactional(
            Recipient("owner@example.test", "Pat Owner"),
            RenderedEmail("Your invitation", "Hello <Pat> & co,\n\nOpen https://seebrook.cc/invite#token=abc"),
        )

        val call = calls.single()
        call.path shouldBe "/v3/smtp/email"
        call.apiKey shouldBe "test-api-key"
        call.body.getValue("sender").jsonObject.str("email") shouldBe "no-reply@seebrook.test"
        call.body.getValue("sender").jsonObject.str("name") shouldBe "Seebrook"
        call.body.getValue("replyTo").jsonObject.str("email") shouldBe "board@example.test"
        call.body.getValue("to").jsonArray.single().jsonObject.str("name") shouldBe "Pat Owner"
        call.body.str("subject") shouldBe "Your invitation"
        call.body.str("textContent") shouldContain "Hello <Pat> & co,"
        val html = call.body.str("htmlContent")
        html shouldContain "<p>Hello &lt;Pat&gt; &amp; co,</p>"
        html shouldContain "https://seebrook.cc/invite#token=abc"
        html shouldNotContain "<a " // no anchors for a click tracker to rewrite
        call.body.keys shouldBe setOf("sender", "replyTo", "to", "subject", "textContent", "htmlContent")
    }

    @Test
    fun `our own HTML is sent as is`() {
        gateway.sendTransactional(Recipient("owner@example.test"), RenderedEmail("Notice", "Plain", "<p>Rich</p>"))
        calls.single().body.str("htmlContent") shouldBe "<p>Rich</p>"
    }

    @Test
    fun `a 400 is a permanent rejection while account, rate and server problems are retried`() {
        fun failWith(status: Int, code: String): EmailDeliveryException {
            reply = { status to """{"code":"$code","message":"owner@example.test is bad"}""" }
            return shouldThrow<EmailDeliveryException> {
                gateway.sendTransactional(Recipient("owner@example.test"), RenderedEmail("s", "t"))
            }
        }

        val rejected = failWith(400, "invalid_parameter")
        rejected.shouldBeInstanceOf<EmailRejectedException>()
        rejected.message shouldContain "HTTP 400, invalid_parameter"
        rejected.message shouldNotContain "owner@example.test"
        rejected.message shouldNotContain "test-api-key"

        failWith(400, "not_enough_credits").shouldNotBeInstanceOf<EmailRejectedException>()
        failWith(401, "unauthorized").shouldNotBeInstanceOf<EmailRejectedException>()
        failWith(429, "too_many_requests").shouldNotBeInstanceOf<EmailRejectedException>()
        failWith(503, "unavailable").shouldNotBeInstanceOf<EmailRejectedException>()
    }

    @Test
    fun `an unreachable API is a temporary failure`() {
        server.stop(0)
        shouldThrow<EmailDeliveryException> { gateway.sendTransactional(Recipient("owner@example.test"), RenderedEmail("s", "t")) }
            .shouldNotBeInstanceOf<EmailRejectedException>()
    }

    @Test
    fun `a malformed address is rejected without calling the API`() {
        shouldThrow<EmailRejectedException> { gateway.sendTransactional(Recipient("not an address"), RenderedEmail("s", "t")) }
        calls.size shouldBe 0
    }

    @Test
    fun `bulk is one request with a personal message per recipient`() {
        val template = EmailTemplate { r -> RenderedEmail("Meeting", "Dear ${r.name}") }

        val result = gateway.sendBulk(
            UUID.randomUUID(),
            listOf(Recipient("a@example.test", "Ann"), Recipient("not an address", "X"), Recipient("b@example.test", "Bo")),
            template,
        )

        result shouldBe BulkResult(2, listOf(Recipient("not an address", "X")))
        val versions = calls.single().body.getValue("messageVersions").jsonArray.map { it.jsonObject }
        versions.map { v -> v.getValue("to").jsonArray.single().jsonObject.str("email") } shouldBe
            listOf("a@example.test", "b@example.test")
        versions.map { it.str("textContent") } shouldBe listOf("Dear Ann", "Dear Bo")
    }

    @Test
    fun `a refused batch falls back to one-by-one so only bad recipients fail`() {
        reply = { body ->
            if ("messageVersions" in body || body.toString().contains("bounce@example.test")) {
                400 to """{"code":"invalid_parameter","message":"invalid"}"""
            } else {
                201 to """{"messageId":"m"}"""
            }
        }

        val result = gateway.sendBulk(
            UUID.randomUUID(),
            listOf(Recipient("a@example.test"), Recipient("bounce@example.test"), Recipient("b@example.test")),
        ) { RenderedEmail("Meeting", "Hello") }

        result shouldBe BulkResult(2, listOf(Recipient("bounce@example.test")))
        calls.size shouldBe 4
    }

    @Test
    fun `a temporary failure of the whole batch is reported to the caller`() {
        reply = { 503 to "{}" }
        shouldThrow<EmailDeliveryException> {
            gateway.sendBulk(UUID.randomUUID(), listOf(Recipient("a@example.test"))) { RenderedEmail("s", "t") }
        }
    }
}
