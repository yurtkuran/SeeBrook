package cc.seebrook.platform.payments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/**
 * A provider that moves no money, for development and tests (ED-43); never allowed in production.
 * Payments are accepted as created; their outcome arrives as a signed webhook, sent by
 * `seebrook dev-payment-event` or a test, exactly as a real provider's would. It keeps no state,
 * so the server and the CLI agree without sharing memory.
 */
class SimulatedPaymentProvider(
    private val webhookSecret: String,
    private val clock: Clock = Clock.systemUTC(),
) : PaymentProvider {
    override val name: String = NAME

    override fun createPayment(request: CreatePaymentRequest): ProviderPayment {
        require(request.amountCents > 0) { "amount must be positive" }
        if (!request.merchantRef.startsWith(MERCHANT_PREFIX)) throw PaymentRequestRejectedException("unknown simulated merchant")
        return ProviderPayment(paymentId(request.merchantRef, request.idempotencyKey), ProviderPaymentStatus.AWAITING_OWNER, mapOf("simulated" to "true"))
    }

    /** Keeps no state: every payment looks unfinished, so reconciliation leaves it to the webhooks. */
    override fun lookupPayment(merchantRef: String, providerPaymentId: String) =
        ProviderPaymentSnapshot(providerPaymentId, ProviderPaymentStatus.AWAITING_OWNER, 0, "USD", null)

    override fun checkAvailable(merchantRef: String) {
        if (!merchantRef.startsWith(MERCHANT_PREFIX)) throw PaymentRequestRejectedException("unknown simulated merchant")
    }

    override fun verifyWebhook(payload: ByteArray, header: (String) -> String?): WebhookVerification {
        val signature = header(SIGNATURE_HEADER) ?: return WebhookVerification.Rejected("missing $SIGNATURE_HEADER header")
        WebhookSignature.verify(payload, signature, webhookSecret, clock)?.let { return WebhookVerification.Rejected(it) }
        val event = runCatching { Json.parseToJsonElement(payload.decodeToString()).jsonObject }.getOrNull()
            ?: return WebhookVerification.Rejected("payload is not a JSON object")
        fun text(key: String) = (event[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = text("id") ?: return WebhookVerification.Rejected("event has no ID")
        text("adjustment")?.let { code ->
            val kind = AdjustmentKind.fromCode(code) ?: return WebhookVerification.Rejected("unknown adjustment '$code'")
            return WebhookVerification.Verified(
                PaymentEvent.Adjusted(
                    eventId = id,
                    type = "payment.$code",
                    merchantRef = text("merchant").orEmpty(),
                    providerPaymentId = text("payment") ?: return WebhookVerification.Rejected("event has no payment"),
                    kind = kind,
                    providerRef = text("ref") ?: return WebhookVerification.Rejected("event has no ref"),
                    amountCents = (event["amount"] as? JsonPrimitive)?.longOrNull ?: return WebhookVerification.Rejected("event has no amount"),
                    currency = text("currency") ?: "USD",
                ),
            )
        }
        val status = text("status")?.let { s -> ProviderPaymentStatus.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
            ?: return WebhookVerification.Verified(PaymentEvent.Other(id, "unknown"))
        return WebhookVerification.Verified(
            PaymentEvent.PaymentUpdated(
                eventId = id,
                type = "payment.${status.name.lowercase()}",
                merchantRef = text("merchant").orEmpty(),
                providerPaymentId = text("payment") ?: return WebhookVerification.Rejected("event has no payment"),
                status = status,
                amountCents = (event["amount"] as? JsonPrimitive)?.longOrNull ?: return WebhookVerification.Rejected("event has no amount"),
                currency = text("currency") ?: "USD",
                seebrookPaymentId = text("seebrookPaymentId"),
                failureCode = text("failureCode"),
            ),
        )
    }

    /** A signed event, as the provider would send it: (payload, signature header value). */
    fun event(
        merchantRef: String,
        providerPaymentId: String,
        status: ProviderPaymentStatus,
        amountCents: Long,
        eventId: String = "sim_evt_${UUID.randomUUID()}",
        failureCode: String? = null,
    ): Pair<ByteArray, String> {
        val payload = buildJsonObject {
            put("id", eventId)
            put("merchant", merchantRef)
            put("payment", providerPaymentId)
            put("status", status.name.lowercase())
            put("amount", amountCents)
            put("currency", "USD")
            failureCode?.let { put("failureCode", it) }
        }.toString().toByteArray()
        return payload to WebhookSignature.header(payload, webhookSecret, clock)
    }

    /** A signed refund or dispute event for a payment: (payload, signature header value). */
    fun adjustment(
        merchantRef: String,
        providerPaymentId: String,
        kind: AdjustmentKind,
        providerRef: String,
        amountCents: Long,
        eventId: String = "sim_evt_${UUID.randomUUID()}",
    ): Pair<ByteArray, String> {
        val payload = buildJsonObject {
            put("id", eventId)
            put("merchant", merchantRef)
            put("payment", providerPaymentId)
            put("adjustment", kind.code)
            put("ref", providerRef)
            put("amount", amountCents)
            put("currency", "USD")
        }.toString().toByteArray()
        return payload to WebhookSignature.header(payload, webhookSecret, clock)
    }

    override fun toString(): String = "SimulatedPaymentProvider(secret=****)"

    companion object {
        const val NAME = "simulated"
        const val SIGNATURE_HEADER = "Seebrook-Signature"
        const val MERCHANT_PREFIX = "sim_"

        /** The same request always gets the same payment, like a real provider's idempotency. */
        fun paymentId(merchantRef: String, idempotencyKey: String): String =
            "sim_pay_" + MessageDigest.getInstance("SHA-256").digest("$merchantRef/$idempotencyKey".toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
    }
}
