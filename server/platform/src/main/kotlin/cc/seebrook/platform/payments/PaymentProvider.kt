package cc.seebrook.platform.payments

/** How an owner pays. Wallets (Apple Pay, Google Pay) are cards. */
enum class PaymentMethod(val code: String) {
    CARD("card"),
    ACH("ach"),
    ;

    companion object {
        fun fromCode(code: String): PaymentMethod? = entries.firstOrNull { it.code == code.trim().lowercase() }
    }
}

/** A provider payment's state, in Seebrook's terms. Every adapter maps its provider's states onto these. */
enum class ProviderPaymentStatus {
    /** Created; the owner has not finished paying (or must try another method). */
    AWAITING_OWNER,

    /** Submitted and not settled yet (bank payments take days). */
    PROCESSING,
    SUCCEEDED,

    /** This attempt failed; the provider may still let the owner retry it. */
    FAILED,
    CANCELED,
}

/**
 * Asks a provider to create one payment. [idempotencyKey] makes a retried request return the
 * same provider payment. [metadata] carries Seebrook IDs only, never personal or card data.
 */
data class CreatePaymentRequest(
    val merchantRef: String,
    val amountCents: Long,
    val currency: String,
    val method: PaymentMethod,
    val idempotencyKey: String,
    val description: String,
    val metadata: Map<String, String>,
)

/**
 * The provider's payment. [clientParams] is what the provider's own app SDK needs to collect the
 * owner's card or bank details (GA-24, X-02): never a secret key. It is passed to the app as is
 * and never stored or logged.
 */
class ProviderPayment(
    val providerPaymentId: String,
    val status: ProviderPaymentStatus,
    val clientParams: Map<String, String>,
) {
    override fun toString(): String = "ProviderPayment(providerPaymentId=$providerPaymentId, status=$status, clientParams=****)"
}

/** A verified provider event, normalized. */
sealed interface PaymentEvent {
    val eventId: String
    val type: String

    /** A payment changed state. */
    data class PaymentUpdated(
        override val eventId: String,
        override val type: String,
        val merchantRef: String,
        val providerPaymentId: String,
        val status: ProviderPaymentStatus,
        val amountCents: Long,
        val currency: String,
        /** Seebrook's payment ID from the metadata, when present. */
        val seebrookPaymentId: String?,
        /** A short provider code (e.g. `card_declined`), never a message with personal data. */
        val failureCode: String?,
    ) : PaymentEvent

    /** Genuine, but of a kind Seebrook does not act on (yet). */
    data class Other(override val eventId: String, override val type: String) : PaymentEvent
}

sealed interface WebhookVerification {
    data class Verified(val event: PaymentEvent) : WebhookVerification

    /** Not from the provider, too old, or unreadable. [reason] never contains the payload. */
    data class Rejected(val reason: String) : WebhookVerification
}

/** The provider could not be reached or failed; the caller may retry later. */
open class PaymentProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The provider refused the request for good (e.g. an unknown merchant account). */
class PaymentRequestRejectedException(message: String) : PaymentProviderException(message)

/**
 * The only way the application talks to a payment processor (ED-43). Business code depends on
 * this interface alone; each processor is an adapter behind it, chosen per HOA by its payment
 * settings, so a processor can be added, replaced or removed without touching business logic.
 * Card and bank details are collected by the processor's own SDK and never pass through here.
 */
interface PaymentProvider {
    /** Stable name stored with each payment and HOA setting, e.g. `stripe`. */
    val name: String

    /** Creates a payment for exactly [CreatePaymentRequest.amountCents]; idempotent on the key. */
    fun createPayment(request: CreatePaymentRequest): ProviderPayment

    /** The payment's current state at the provider, for reconciliation. */
    fun paymentStatus(merchantRef: String, providerPaymentId: String): ProviderPaymentStatus

    /** Checks an incoming webhook's signature and age, then normalizes it. */
    fun verifyWebhook(payload: ByteArray, header: (String) -> String?): WebhookVerification
}

/** The providers this deployment can use, by name. Empty when payments are disabled. */
class PaymentProviders(providers: List<PaymentProvider>) {
    private val byName = providers.associateBy { it.name }

    init {
        require(byName.size == providers.size) { "duplicate payment provider names" }
    }

    operator fun get(name: String): PaymentProvider? = byName[name]

    val names: Set<String> get() = byName.keys

    companion object {
        val NONE = PaymentProviders(emptyList())
    }
}
