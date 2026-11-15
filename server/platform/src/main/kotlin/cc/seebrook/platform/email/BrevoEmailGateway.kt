package cc.seebrook.platform.email

import jakarta.mail.internet.InternetAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Delivers email through Brevo's transactional HTTP API (ED-36). Only finished messages are sent:
 * no Brevo templates, contacts or lists. Works on hosts that block SMTP ports, and tells apart a
 * permanent rejection (400) from a temporary failure, which the caller retries.
 */
class BrevoEmailGateway(
    private val apiKey: String,
    from: String,
    replyTo: String? = null,
    baseUrl: String = DEFAULT_BASE_URL,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : EmailGateway {
    private val log = LoggerFactory.getLogger(BrevoEmailGateway::class.java)
    private val endpoint = URI("${baseUrl.trimEnd('/')}/smtp/email")
    private val sender = InternetAddress(from, true)
    private val replyToAddress = replyTo?.let { InternetAddress(it, true) }

    override fun sendTransactional(to: Recipient, email: RenderedEmail) {
        if (!looksLikeAddress(to.email)) throw EmailRejectedException("Recipient address is not valid")
        post(
            message {
                putJsonArray("to") { addJsonObject { recipient(to) } }
                content(email)
            },
        )
    }

    override fun sendBulk(hoaId: UUID, recipients: List<Recipient>, template: EmailTemplate): BulkResult {
        require(recipients.size <= EmailGateway.MAX_BULK_RECIPIENTS) { "too many recipients for one bulk send" }
        val (valid, invalid) = recipients.partition { looksLikeAddress(it.email) }
        if (valid.isEmpty()) return BulkResult(0, invalid)
        val rendered = valid.map { it to template.render(it) }
        val failed = invalid.toMutableList()
        try {
            // One request; each version is a separate message to one recipient.
            post(
                message {
                    // Brevo requires top-level content even when every version has its own.
                    content(rendered.first().second)
                    putJsonArray("messageVersions") {
                        rendered.forEach { (recipient, email) ->
                            addJsonObject {
                                putJsonArray("to") { addJsonObject { recipient(recipient) } }
                                content(email)
                            }
                        }
                    }
                },
            )
        } catch (e: EmailRejectedException) {
            // The batch was refused as a whole; send one by one so only the bad recipients fail.
            rendered.forEach { (recipient, email) ->
                try {
                    sendTransactional(recipient, email)
                } catch (_: EmailRejectedException) {
                    failed += recipient
                }
            }
        }
        log.info("Bulk email for HOA {}: {} sent, {} failed", hoaId, recipients.size - failed.size, failed.size)
        return BulkResult(recipients.size - failed.size, failed)
    }

    private fun message(body: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        putJsonObject("sender") {
            put("email", sender.address)
            sender.personal?.let { put("name", it.take(70)) }
        }
        replyToAddress?.let { address ->
            putJsonObject("replyTo") {
                put("email", address.address)
                address.personal?.let { put("name", it.take(70)) }
            }
        }
        body()
    }

    private fun JsonObjectBuilder.recipient(recipient: Recipient) {
        put("email", recipient.email)
        recipient.name?.let { put("name", it.take(70)) }
    }

    private fun JsonObjectBuilder.content(email: RenderedEmail) {
        put("subject", email.subject)
        put("textContent", email.text)
        // Brevo requires HTML; plain-text emails get an escaped HTML copy of the text.
        put("htmlContent", email.html ?: PlainTextHtml.render(email.text))
    }

    private fun post(body: JsonObject) {
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("api-key", apiKey)
            .header("accept", "application/json")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw EmailDeliveryException("Brevo request failed (${e.javaClass.simpleName})", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EmailDeliveryException("Brevo request interrupted", e)
        }
        val status = response.statusCode()
        if (status in 200..299) return
        // Status and Brevo's error code only: the body can echo addresses.
        val code = errorCode(response.body())
        val reason = "Brevo refused the request (HTTP $status${code?.let { ", $it" }.orEmpty()})"
        throw if (status == 400 && code !in ACCOUNT_ERRORS) EmailRejectedException(reason) else EmailDeliveryException(reason)
    }

    private fun errorCode(body: String): String? = runCatching {
        (Json.parseToJsonElement(body) as JsonObject)["code"]?.jsonPrimitive?.content?.take(60)
    }.getOrNull()

    private fun looksLikeAddress(email: String) = runCatching { InternetAddress(email, true) }.isSuccess && '@' in email

    companion object {
        const val DEFAULT_BASE_URL = "https://api.brevo.com/v3"

        /** 400 codes about our account, not the message: fixable, so retried. */
        private val ACCOUNT_ERRORS = setOf("not_enough_credits", "Insufficient credits", "unauthorized", "Authentication failed")
    }
}

/** An HTML copy of a plain-text email for providers that require HTML: escaped, paragraphs and line breaks kept. */
object PlainTextHtml {
    fun render(text: String): String {
        val paragraphs = text.trim().split(Regex("\n\\s*\n")).joinToString("\n") { paragraph ->
            "<p>" + escape(paragraph).replace("\n", "<br>\n") + "</p>"
        }
        return "<!doctype html><html><body style=\"font-family: sans-serif; line-height: 1.5\">\n$paragraphs\n</body></html>"
    }

    private fun escape(value: String) = buildString {
        value.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(it)
            }
        }
    }
}
