package cc.seebrook.platform.payments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stripe adapter (ED-43): Stripe Connect with direct charges, so every payment is made on the
 * HOA's own connected Stripe account ([CreatePaymentRequest.merchantRef], `acct_…`) and the money
 * goes to the HOA. Seebrook holds one platform key; HOAs need no secrets of their own. Payments are
 * PaymentIntents limited to the method the owner chose, so the fee they approved is exact. Calls
 * Stripe's HTTP API directly with a pinned API version.
 */
class StripePaymentProvider(
    private val secretKey: String,
    private val publishableKey: String,
    private val webhookSecret: String,
    apiBase: String = DEFAULT_API_BASE,
    private val clock: Clock = Clock.systemUTC(),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : PaymentProvider {
    private val log = LoggerFactory.getLogger(StripePaymentProvider::class.java)
    private val base = apiBase.trimEnd('/')
    private val liveMode = secretKey.startsWith("sk_live_") || secretKey.startsWith("rk_live_")

    override val name: String = NAME

    override fun createPayment(request: CreatePaymentRequest): ProviderPayment {
        require(request.amountCents > 0) { "amount must be positive" }
        val form = buildList {
            add("amount" to request.amountCents.toString())
            add("currency" to request.currency.lowercase())
            add("allowed_payment_method_types[]" to methodType(request.method))
            add("description" to request.description)
            request.metadata.forEach { (k, v) -> add("metadata[$k]" to v) }
        }
        val intent = call(
            HttpRequest.newBuilder(URI("$base/v1/payment_intents"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Idempotency-Key", request.idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(form.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" })),
            request.merchantRef,
        )
        val id = intent.string("id") ?: throw PaymentProviderException("Stripe returned a payment without an ID")
        val clientSecret = intent.string("client_secret") ?: throw PaymentProviderException("Stripe returned a payment without a client secret")
        return ProviderPayment(
            providerPaymentId = id,
            status = status(intent),
            clientParams = mapOf(
                "clientSecret" to clientSecret,
                "publishableKey" to publishableKey,
                "stripeAccount" to request.merchantRef,
            ),
        )
    }

    override fun paymentStatus(merchantRef: String, providerPaymentId: String): ProviderPaymentStatus {
        require(PAYMENT_INTENT_ID.matches(providerPaymentId)) { "not a PaymentIntent ID" }
        return status(call(HttpRequest.newBuilder(URI("$base/v1/payment_intents/$providerPaymentId")).GET(), merchantRef))
    }

    override fun verifyWebhook(payload: ByteArray, header: (String) -> String?): WebhookVerification {
        val signature = header("Stripe-Signature") ?: return WebhookVerification.Rejected("missing Stripe-Signature header")
        when (val check = WebhookSignature.verify(payload, signature, webhookSecret, clock)) {
            null -> {}
            else -> return WebhookVerification.Rejected(check)
        }
        val event = runCatching { Json.parseToJsonElement(payload.decodeToString()).jsonObject }.getOrNull()
            ?: return WebhookVerification.Rejected("payload is not a JSON object")
        val eventId = event.string("id") ?: return WebhookVerification.Rejected("event has no ID")
        val type = event.string("type") ?: return WebhookVerification.Rejected("event has no type")
        val live = (event["livemode"] as? JsonPrimitive)?.booleanOrNull
        if (live != liveMode) return WebhookVerification.Rejected("event livemode does not match this deployment")

        val newStatus = EVENT_STATUS[type] ?: return WebhookVerification.Verified(PaymentEvent.Other(eventId, type))
        val intent = (event["data"] as? JsonObject)?.get("object") as? JsonObject
            ?: return WebhookVerification.Rejected("event has no data object")
        val error = intent["last_payment_error"] as? JsonObject
        return WebhookVerification.Verified(
            PaymentEvent.PaymentUpdated(
                eventId = eventId,
                type = type,
                merchantRef = event.string("account").orEmpty(),
                providerPaymentId = intent.string("id") ?: return WebhookVerification.Rejected("payment has no ID"),
                status = newStatus,
                amountCents = (intent["amount"] as? JsonPrimitive)?.longOrNull ?: return WebhookVerification.Rejected("payment has no amount"),
                currency = intent.string("currency")?.uppercase() ?: return WebhookVerification.Rejected("payment has no currency"),
                seebrookPaymentId = (intent["metadata"] as? JsonObject)?.string(METADATA_PAYMENT_ID),
                failureCode = if (newStatus == ProviderPaymentStatus.FAILED) (error?.string("decline_code") ?: error?.string("code"))?.take(64) else null,
            ),
        )
    }

    private fun call(builder: HttpRequest.Builder, merchantRef: String): JsonObject {
        require(CONNECTED_ACCOUNT.matches(merchantRef)) { "not a Stripe connected account ID" }
        val request = builder
            .header("Authorization", "Bearer $secretKey")
            .header("Stripe-Account", merchantRef)
            .header("Stripe-Version", API_VERSION)
            .timeout(Duration.ofSeconds(30))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw PaymentProviderException("Stripe could not be reached", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PaymentProviderException("Interrupted while calling Stripe", e)
        }
        val body = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        val status = response.statusCode()
        if (status in 200..299 && body != null) return body
        // Only Stripe's error code is kept: messages can echo request details.
        val code = (body?.get("error") as? JsonObject)?.string("code") ?: "http_$status"
        log.warn("Stripe request failed: HTTP {} ({})", status, code)
        if (status == 429 || status >= 500 || status == 409) throw PaymentProviderException("Stripe is unavailable ($code)")
        throw PaymentRequestRejectedException("Stripe refused the request ($code)")
    }

    private fun status(intent: JsonObject): ProviderPaymentStatus = when (intent.string("status")) {
        "processing" -> ProviderPaymentStatus.PROCESSING
        "succeeded" -> ProviderPaymentStatus.SUCCEEDED
        "canceled" -> ProviderPaymentStatus.CANCELED
        "requires_payment_method" ->
            if (intent["last_payment_error"].let { it != null && it != JsonNull }) ProviderPaymentStatus.FAILED else ProviderPaymentStatus.AWAITING_OWNER
        else -> ProviderPaymentStatus.AWAITING_OWNER
    }

    private fun methodType(method: PaymentMethod) = when (method) {
        PaymentMethod.CARD -> "card"
        PaymentMethod.ACH -> "us_bank_account"
    }

    override fun toString(): String = "StripePaymentProvider(base=$base, liveMode=$liveMode, keys=****)"

    companion object {
        const val NAME = "stripe"
        const val DEFAULT_API_BASE = "https://api.stripe.com"

        /** Pinned so a Stripe-side default change cannot alter behavior unnoticed. */
        const val API_VERSION = "2026-09-30.endive"
        const val METADATA_PAYMENT_ID = "seebrook_payment_id"

        private val CONNECTED_ACCOUNT = Regex("^acct_[A-Za-z0-9]{1,64}$")
        private val PAYMENT_INTENT_ID = Regex("^pi_[A-Za-z0-9]{1,128}$")
        private val EVENT_STATUS = mapOf(
            "payment_intent.processing" to ProviderPaymentStatus.PROCESSING,
            "payment_intent.succeeded" to ProviderPaymentStatus.SUCCEEDED,
            "payment_intent.payment_failed" to ProviderPaymentStatus.FAILED,
            "payment_intent.canceled" to ProviderPaymentStatus.CANCELED,
        )

        fun isConnectedAccount(value: String) = CONNECTED_ACCOUNT.matches(value)

        private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Stripe's webhook signature scheme, also used by the simulated provider: header
 * `t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<payload>">` (several `v1` allowed while a secret
 * rotates), accepted for [TOLERANCE] either side of now so an old captured event cannot be replayed.
 */
object WebhookSignature {
    val TOLERANCE: Duration = Duration.ofMinutes(5)

    /** Null when valid, otherwise why not. */
    fun verify(payload: ByteArray, header: String, secret: String, clock: Clock): String? {
        val parts = header.split(',').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
        val timestamp = parts.firstOrNull { it.first == "t" }?.second?.toLongOrNull() ?: return "signature has no timestamp"
        val signatures = parts.filter { it.first == "v1" }.map { it.second }
        if (signatures.isEmpty()) return "signature has no v1 value"
        val expected = sign(payload, timestamp, secret).toByteArray()
        if (signatures.none { MessageDigest.isEqual(it.toByteArray(), expected) }) return "signature does not match"
        val age = clock.instant().epochSecond - timestamp
        if (age > TOLERANCE.seconds || age < -TOLERANCE.seconds) return "signature timestamp is outside the tolerance"
        return null
    }

    fun sign(payload: ByteArray, timestamp: Long, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        mac.update("$timestamp.".toByteArray())
        return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
    }

    fun header(payload: ByteArray, secret: String, clock: Clock): String {
        val t = clock.instant().epochSecond
        return "t=$t,v1=${sign(payload, t, secret)}"
    }
}
