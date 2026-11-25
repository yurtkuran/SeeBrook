package cc.seebrook.platform.payments

import java.time.LocalDate

/** How an owner pays. Wallets (Apple Pay, Google Pay) are cards. */
enum class PaymentMethod(val code: String) {
    CARD("card"),
    ACH("ach"),
    ;

    companion object {
        fun fromCode(code: String): PaymentMethod? = entries.firstOrNull { it.code == code.trim().lowercase() }
    }
}

/** What happened to a received payment's money (ED-45). */
enum class AdjustmentKind(val code: String) {
    /** Money returned to the owner. */
    REFUND("refund"),

    /** A refund that did not go through: the money is back with the HOA. */
    REFUND_FAILED("refund_failed"),

    /** A dispute or ACH return took the money back from the HOA. */
    DISPUTE_WITHDRAWN("dispute_withdrawn"),

    /** A dispute was decided for the HOA: the money is back. */
    DISPUTE_REINSTATED("dispute_reinstated"),
    ;

    /** True when this kind returns money to the HOA, undoing an earlier refund or withdrawal. */
    val restores: Boolean get() = this == REFUND_FAILED || this == DISPUTE_REINSTATED

    companion object {
        fun fromCode(code: String): AdjustmentKind? = entries.firstOrNull { it.code == code }
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
    /** A short provider code when a charge of a saved method was declined at once (ED-59). */
    val failureCode: String? = null,
) {
    override fun toString(): String = "ProviderPayment(providerPaymentId=$providerPaymentId, status=$status, clientParams=****)"
}

/**
 * Asks a provider to start saving a payment method for later charges (ED-59). [customerRef] is the
 * owner's existing provider customer, or null to create one ([customerKey] makes that creation
 * idempotent). [metadata] carries Seebrook IDs only.
 */
data class CreateSetupRequest(
    val merchantRef: String,
    val method: PaymentMethod,
    val customerRef: String?,
    val customerKey: String,
    val idempotencyKey: String,
    val metadata: Map<String, String>,
)

/** A started setup: the provider's IDs, and what its app SDK needs to collect the details (X-02). */
class ProviderSetup(
    val setupId: String,
    val customerRef: String,
    val clientParams: Map<String, String>,
) {
    override fun toString(): String = "ProviderSetup(setupId=$setupId, clientParams=****)"
}

enum class SetupStatus {
    /** The method is saved and can be charged. */
    SUCCEEDED,

    /** Still being verified (e.g. bank micro-deposits). */
    PENDING,

    /** The owner has not finished, or it was refused; nothing is saved. */
    NOT_COMPLETED,
}

/**
 * A setup as the provider holds it. When [status] is SUCCEEDED, [methodRef] is the saved method,
 * [method] its kind and [label] how the owner recognizes it (brand or bank and the last four digits).
 */
data class SetupSnapshot(
    val setupId: String,
    val customerRef: String?,
    val status: SetupStatus,
    val methodRef: String?,
    val method: PaymentMethod?,
    val label: String?,
)

/** Charges a saved method without the owner present, for exactly [amountCents] (ED-59). */
data class SavedChargeRequest(
    val merchantRef: String,
    val customerRef: String,
    val methodRef: String,
    val method: PaymentMethod,
    val amountCents: Long,
    val currency: String,
    val idempotencyKey: String,
    val description: String,
    val metadata: Map<String, String>,
)

/**
 * A payment as the provider holds it right now, for reconciliation (ED-46): the same facts a
 * webhook carries, so a looked-up state is checked and applied exactly like an event.
 */
data class ProviderPaymentSnapshot(
    val providerPaymentId: String,
    val status: ProviderPaymentStatus,
    val amountCents: Long,
    val currency: String,
    /** A short provider code (e.g. `card_declined`) when [status] is FAILED. */
    val failureCode: String?,
)

enum class PayoutStatus { PENDING, IN_TRANSIT, PAID, FAILED, CANCELED }

/** One transfer of settled money from the provider to the HOA's bank account (ED-47). */
data class ProviderPayout(
    val payoutId: String,
    /** What reaches the bank: the net of all its lines. */
    val amountCents: Long,
    val currency: String,
    val status: PayoutStatus,
    /** The day the bank receives it. */
    val arrivalDate: LocalDate,
)

/** What a settlement line is, in Seebrook's terms. */
enum class SettlementKind { PAYMENT, REFUND, REFUND_FAILED, DISPUTE, DISPUTE_REVERSAL, FEE, OTHER }

/**
 * One money movement inside a payout (ED-47). [grossCents] is the amount moved (negative when money
 * left the HOA), [feeCents] the provider's own fee on it (positive is a cost), and
 * [netCents] = gross − fee. [sourceRef] is the provider's charge, refund or dispute ID;
 * [providerPaymentId] the payment it belongs to, when there is one.
 */
data class SettlementLine(
    val kind: SettlementKind,
    val sourceRef: String?,
    val providerPaymentId: String?,
    val grossCents: Long,
    val feeCents: Long,
    val netCents: Long,
    val currency: String,
)

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

    /**
     * Money moved after a payment was received (GA-19): a refund to the owner or its failure, or a
     * dispute (ACH returns included) taking the funds back or reinstating them. [providerRef]
     * identifies the refund or dispute; [amountCents] is the money that moved, always positive.
     */
    data class Adjusted(
        override val eventId: String,
        override val type: String,
        val merchantRef: String,
        val providerPaymentId: String,
        val kind: AdjustmentKind,
        val providerRef: String,
        val amountCents: Long,
        val currency: String,
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

    /** The payment as the provider holds it now, for reconciliation (ED-46). */
    fun lookupPayment(merchantRef: String, providerPaymentId: String): ProviderPaymentSnapshot

    /**
     * Returns normally when the provider answers for this merchant account; throws
     * [PaymentProviderException] when it cannot be reached. Used to end an automatic outage
     * suspension (ED-46). Moves no money and changes nothing.
     */
    fun checkAvailable(merchantRef: String)

    /** Payouts to the HOA's bank arriving on [from] through [to] (inclusive), oldest first (ED-47). */
    fun payouts(merchantRef: String, from: LocalDate, to: LocalDate): List<ProviderPayout>

    /** The money movements a payout is made of, without the payout itself (ED-47). */
    fun payoutLines(merchantRef: String, payoutId: String): List<SettlementLine>

    /** Checks an incoming webhook's signature and age, then normalizes it. */
    fun verifyWebhook(payload: ByteArray, header: (String) -> String?): WebhookVerification

    /** Starts saving a payment method for the owner (ED-59); idempotent on the key. */
    fun createSetup(request: CreateSetupRequest): ProviderSetup

    /** The setup as the provider holds it now; the app's word that it finished is never trusted. */
    fun lookupSetup(merchantRef: String, setupId: String): SetupSnapshot

    /**
     * Charges a saved method; idempotent on the key. A decline returns a FAILED payment with its
     * code (the provider still sends its event); a refused request throws [PaymentRequestRejectedException].
     */
    fun chargeSaved(request: SavedChargeRequest): ProviderPayment

    /** Stops the provider keeping a saved method. */
    fun removeSaved(merchantRef: String, methodRef: String)
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
