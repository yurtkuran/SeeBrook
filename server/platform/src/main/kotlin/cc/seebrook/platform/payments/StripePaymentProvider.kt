package cc.seebrook.platform.payments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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

    /**
     * Saving a method for recurring plans (ED-59): a Customer on the HOA's connected account (no
     * personal details; Seebrook IDs as metadata), then a SetupIntent for off-session use limited to
     * the chosen method. The app's payment sheet collects the details against it.
     */
    override fun createSetup(request: CreateSetupRequest): ProviderSetup {
        val customer = request.customerRef ?: run {
            val created = post("/v1/customers", request.merchantRef, "seebrook-customer-${request.customerKey}") {
                request.metadata.forEach { (k, v) -> add("metadata[$k]" to v) }
            }
            created.string("id") ?: throw PaymentProviderException("Stripe returned a customer without an ID")
        }
        val setup = post("/v1/setup_intents", request.merchantRef, request.idempotencyKey) {
            add("customer" to customer)
            add("usage" to "off_session")
            add("allowed_payment_method_types[]" to methodType(request.method))
            request.metadata.forEach { (k, v) -> add("metadata[$k]" to v) }
        }
        val id = setup.string("id") ?: throw PaymentProviderException("Stripe returned a setup without an ID")
        val clientSecret = setup.string("client_secret") ?: throw PaymentProviderException("Stripe returned a setup without a client secret")
        return ProviderSetup(
            setupId = id,
            customerRef = customer,
            clientParams = mapOf(
                "setupClientSecret" to clientSecret,
                "publishableKey" to publishableKey,
                "stripeAccount" to request.merchantRef,
            ),
        )
    }

    override fun lookupSetup(merchantRef: String, setupId: String): SetupSnapshot {
        require(SETUP_INTENT_ID.matches(setupId)) { "not a SetupIntent ID" }
        val setup = call(HttpRequest.newBuilder(URI("$base/v1/setup_intents/$setupId?expand%5B%5D=payment_method")).GET(), merchantRef)
        val status = when (setup.string("status")) {
            "succeeded" -> SetupStatus.SUCCEEDED
            "processing", "requires_action" -> SetupStatus.PENDING
            else -> SetupStatus.NOT_COMPLETED
        }
        val pm = setup["payment_method"] as? JsonObject
        val (method, label) = when (pm?.string("type")) {
            "card" -> {
                val card = pm["card"] as? JsonObject
                val brand = card?.string("display_brand") ?: card?.string("brand") ?: "Card"
                PaymentMethod.CARD to "${brand.replace('_', ' ').replaceFirstChar { it.uppercase() }} •••• ${card?.string("last4") ?: "????"}"
            }
            "us_bank_account" -> {
                val bank = pm["us_bank_account"] as? JsonObject
                PaymentMethod.ACH to "${bank?.string("bank_name") ?: "Bank account"} •••• ${bank?.string("last4") ?: "????"}"
            }
            else -> null to null
        }
        return SetupSnapshot(
            setupId = setup.string("id") ?: setupId,
            customerRef = setup.string("customer"),
            status = if (status == SetupStatus.SUCCEEDED && (pm == null || method == null)) SetupStatus.NOT_COMPLETED else status,
            methodRef = pm?.string("id"),
            method = method,
            label = label?.take(80),
        )
    }

    /**
     * An off-session PaymentIntent confirmed at once on the saved method (ED-59). A decline comes
     * back as HTTP 402 with the failed PaymentIntent; it is returned as FAILED with its code, and
     * Stripe still sends `payment_intent.payment_failed`.
     */
    override fun chargeSaved(request: SavedChargeRequest): ProviderPayment {
        require(request.amountCents > 0) { "amount must be positive" }
        val form = buildList {
            add("amount" to request.amountCents.toString())
            add("currency" to request.currency.lowercase())
            add("customer" to request.customerRef)
            add("payment_method" to request.methodRef)
            add("allowed_payment_method_types[]" to methodType(request.method))
            add("off_session" to "true")
            add("confirm" to "true")
            add("description" to request.description)
            request.metadata.forEach { (k, v) -> add("metadata[$k]" to v) }
        }
        val (status, body) = send(
            HttpRequest.newBuilder(URI("$base/v1/payment_intents"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Idempotency-Key", request.idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(form.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" })),
            request.merchantRef,
        )
        if (status == 402) {
            val error = body?.get("error") as? JsonObject
            val intent = error?.get("payment_intent") as? JsonObject
            val id = intent?.string("id") ?: throw PaymentRequestRejectedException("Stripe declined the charge (${error?.string("code") ?: "declined"})")
            val code = (error.string("decline_code") ?: error.string("code") ?: "declined").take(64)
            return ProviderPayment(id, ProviderPaymentStatus.FAILED, emptyMap(), code)
        }
        val intent = checked(status, body)
        val id = intent.string("id") ?: throw PaymentProviderException("Stripe returned a payment without an ID")
        return ProviderPayment(id, status(intent), emptyMap(), failureCode(intent))
    }

    override fun removeSaved(merchantRef: String, methodRef: String) {
        require(PAYMENT_METHOD_ID.matches(methodRef)) { "not a PaymentMethod ID" }
        post("/v1/payment_methods/$methodRef/detach", merchantRef, null) {}
    }

    private fun post(path: String, merchantRef: String, idempotencyKey: String?, form: MutableList<Pair<String, String>>.() -> Unit): JsonObject {
        val body = buildList(form).joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val builder = HttpRequest.newBuilder(URI("$base$path"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        return call(builder, merchantRef)
    }

    override fun lookupPayment(merchantRef: String, providerPaymentId: String): ProviderPaymentSnapshot {
        require(PAYMENT_INTENT_ID.matches(providerPaymentId)) { "not a PaymentIntent ID" }
        val intent = call(HttpRequest.newBuilder(URI("$base/v1/payment_intents/$providerPaymentId")).GET(), merchantRef)
        val status = status(intent)
        return ProviderPaymentSnapshot(
            providerPaymentId = intent.string("id") ?: throw PaymentProviderException("Stripe returned a payment without an ID"),
            status = status,
            amountCents = (intent["amount"] as? JsonPrimitive)?.longOrNull ?: throw PaymentProviderException("Stripe returned a payment without an amount"),
            currency = intent.string("currency")?.uppercase() ?: throw PaymentProviderException("Stripe returned a payment without a currency"),
            failureCode = if (status == ProviderPaymentStatus.FAILED) failureCode(intent) else null,
        )
    }

    override fun payouts(merchantRef: String, from: LocalDate, to: LocalDate): List<ProviderPayout> {
        require(!to.isBefore(from)) { "to is before from" }
        val params = listOf(
            "arrival_date[gte]" to from.atStartOfDay(ZoneOffset.UTC).toEpochSecond().toString(),
            "arrival_date[lt]" to to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond().toString(),
        )
        return list("/v1/payouts", params, merchantRef).map { p ->
            ProviderPayout(
                payoutId = p.string("id") ?: throw PaymentProviderException("Stripe returned a payout without an ID"),
                amountCents = p.long("amount") ?: throw PaymentProviderException("Stripe returned a payout without an amount"),
                currency = p.string("currency")?.uppercase() ?: throw PaymentProviderException("Stripe returned a payout without a currency"),
                status = PAYOUT_STATUS[p.string("status")] ?: PayoutStatus.PENDING,
                // Stripe gives the arrival day as a timestamp at its start (UTC).
                arrivalDate = Instant.ofEpochSecond(p.long("arrival_date") ?: throw PaymentProviderException("Stripe returned a payout without an arrival date"))
                    .atZone(ZoneOffset.UTC).toLocalDate(),
            )
        }.sortedWith(compareBy({ it.arrivalDate }, { it.payoutId }))
    }

    override fun payoutLines(merchantRef: String, payoutId: String): List<SettlementLine> {
        require(PAYOUT_ID.matches(payoutId)) { "not a payout ID" }
        val params = listOf("payout" to payoutId, "expand[]" to "data.source")
        return list("/v1/balance_transactions", params, merchantRef).mapNotNull { t ->
            val category = t.string("reporting_category")
            if (category == "payout" || category == "payout_reversal") return@mapNotNull null
            // The expanded charge, refund or dispute names its PaymentIntent.
            val source = t["source"] as? JsonObject
            SettlementLine(
                kind = SETTLEMENT_KIND[category] ?: SettlementKind.OTHER,
                sourceRef = source?.string("id") ?: t.string("source"),
                providerPaymentId = source?.string("payment_intent"),
                grossCents = t.long("amount") ?: throw PaymentProviderException("Stripe returned a balance transaction without an amount"),
                feeCents = t.long("fee") ?: 0,
                netCents = t.long("net") ?: throw PaymentProviderException("Stripe returned a balance transaction without a net amount"),
                currency = t.string("currency")?.uppercase() ?: throw PaymentProviderException("Stripe returned a balance transaction without a currency"),
            )
        }
    }

    /** Every page of a Stripe list (at most [MAX_PAGES] of 100). */
    private fun list(path: String, params: List<Pair<String, String>>, merchantRef: String): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val query = (params + ("limit" to "100") + listOfNotNull(after?.let { "starting_after" to it }))
                .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
            val page = call(HttpRequest.newBuilder(URI("$base$path?$query")).GET(), merchantRef)
            val data = (page["data"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            items += data
            if ((page["has_more"] as? JsonPrimitive)?.booleanOrNull != true || data.isEmpty()) return items
            after = data.last().string("id") ?: return items
        }
        throw PaymentProviderException("Stripe list $path has more than ${MAX_PAGES * 100} items")
    }

    /** Reads the connected account's balance: a harmless request that needs Stripe and the account to answer. */
    override fun checkAvailable(merchantRef: String) {
        call(HttpRequest.newBuilder(URI("$base/v1/balance")).GET(), merchantRef)
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

        ADJUSTMENT_EVENTS[type]?.let { kind ->
            // Refund and Dispute objects both carry the PaymentIntent they belong to.
            val obj = (event["data"] as? JsonObject)?.get("object") as? JsonObject
                ?: return WebhookVerification.Rejected("event has no data object")
            return WebhookVerification.Verified(
                PaymentEvent.Adjusted(
                    eventId = eventId,
                    type = type,
                    merchantRef = event.string("account").orEmpty(),
                    providerPaymentId = obj.string("payment_intent") ?: return WebhookVerification.Rejected("$type has no payment_intent"),
                    kind = kind,
                    providerRef = obj.string("id") ?: return WebhookVerification.Rejected("$type has no ID"),
                    amountCents = (obj["amount"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                        ?: return WebhookVerification.Rejected("$type has no amount"),
                    currency = obj.string("currency")?.uppercase() ?: return WebhookVerification.Rejected("$type has no currency"),
                ),
            )
        }
        val newStatus = EVENT_STATUS[type] ?: return WebhookVerification.Verified(PaymentEvent.Other(eventId, type))
        val intent = (event["data"] as? JsonObject)?.get("object") as? JsonObject
            ?: return WebhookVerification.Rejected("event has no data object")
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
                failureCode = if (newStatus == ProviderPaymentStatus.FAILED) failureCode(intent) else null,
            ),
        )
    }

    private fun call(builder: HttpRequest.Builder, merchantRef: String): JsonObject {
        val (status, body) = send(builder, merchantRef)
        return checked(status, body)
    }

    private fun send(builder: HttpRequest.Builder, merchantRef: String): Pair<Int, JsonObject?> {
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
        return response.statusCode() to body
    }

    private fun checked(status: Int, body: JsonObject?): JsonObject {
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

    private fun failureCode(intent: JsonObject): String? {
        val error = intent["last_payment_error"] as? JsonObject
        return (error?.string("decline_code") ?: error?.string("code"))?.take(64)
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
        private val SETUP_INTENT_ID = Regex("^seti_[A-Za-z0-9]{1,128}$")
        private val PAYMENT_METHOD_ID = Regex("^pm_[A-Za-z0-9]{1,128}$")
        private val PAYOUT_ID = Regex("^po_[A-Za-z0-9]{1,128}$")
        private const val MAX_PAGES = 50
        private val PAYOUT_STATUS = mapOf(
            "pending" to PayoutStatus.PENDING,
            "in_transit" to PayoutStatus.IN_TRANSIT,
            "paid" to PayoutStatus.PAID,
            "failed" to PayoutStatus.FAILED,
            "canceled" to PayoutStatus.CANCELED,
        )

        /** By Stripe's `reporting_category`, which Stripe keeps stable for reconciliation. */
        private val SETTLEMENT_KIND = mapOf(
            "charge" to SettlementKind.PAYMENT,
            "refund" to SettlementKind.REFUND,
            "refund_failure" to SettlementKind.REFUND_FAILED,
            "dispute" to SettlementKind.DISPUTE,
            "dispute_reversal" to SettlementKind.DISPUTE_REVERSAL,
            "fee" to SettlementKind.FEE,
        )
        private val EVENT_STATUS = mapOf(
            "payment_intent.processing" to ProviderPaymentStatus.PROCESSING,
            "payment_intent.succeeded" to ProviderPaymentStatus.SUCCEEDED,
            "payment_intent.payment_failed" to ProviderPaymentStatus.FAILED,
            "payment_intent.canceled" to ProviderPaymentStatus.CANCELED,
        )

        private val ADJUSTMENT_EVENTS = mapOf(
            "refund.created" to AdjustmentKind.REFUND,
            "refund.failed" to AdjustmentKind.REFUND_FAILED,
            "charge.dispute.funds_withdrawn" to AdjustmentKind.DISPUTE_WITHDRAWN,
            "charge.dispute.funds_reinstated" to AdjustmentKind.DISPUTE_REINSTATED,
        )

        /** The events a Stripe webhook endpoint must send to Seebrook (ED-43, ED-45). */
        val WEBHOOK_EVENTS: List<String> get() = EVENT_STATUS.keys.toList() + ADJUSTMENT_EVENTS.keys

        fun isConnectedAccount(value: String) = CONNECTED_ACCOUNT.matches(value)

        private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

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
