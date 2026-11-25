package cc.seebrook.finance

import cc.seebrook.db.generated.finance.Tables.LEDGER_ENTRY
import cc.seebrook.db.generated.finance.Tables.PAYMENT
import cc.seebrook.db.generated.finance.Tables.PAYMENT_ADJUSTMENT
import cc.seebrook.db.generated.finance.Tables.PAYMENT_EVENT
import cc.seebrook.db.generated.finance.Tables.PAYMENT_METHOD_FEE
import cc.seebrook.db.generated.finance.Tables.PAYMENT_SETTINGS
import cc.seebrook.db.generated.finance.tables.records.PaymentRecord
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Channel
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.payments.AdjustmentKind
import cc.seebrook.platform.payments.CreatePaymentRequest
import cc.seebrook.platform.payments.PaymentEvent
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProviderException
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.PaymentRequestRejectedException
import cc.seebrook.platform.payments.ProviderPaymentStatus
import cc.seebrook.platform.payments.StripePaymentProvider
import cc.seebrook.platform.payments.WebhookVerification
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The fee an owner pays on top of the dues for one payment method (GA-22): [basisPoints] of the
 * dues (rounded half up to the cent) plus [fixedCents]. Both 0 means the HOA absorbs the
 * processor's fee.
 */
data class FeeRule(val basisPoints: Int, val fixedCents: Long) {
    init {
        require(basisPoints in 0..MAX_BASIS_POINTS) { "fee percentage must be between 0% and 10%" }
        require(fixedCents in 0..MAX_FIXED_CENTS) { "fixed fee must be between $0 and $100" }
    }

    fun feeFor(duesCents: Long): Long = (duesCents * basisPoints + 5_000) / 10_000 + fixedCents

    val absorbed: Boolean get() = basisPoints == 0 && fixedCents == 0L

    companion object {
        const val MAX_BASIS_POINTS = 1000
        const val MAX_FIXED_CENTS = 10_000L
        val NONE = FeeRule(0, 0)
    }
}

/**
 * How one HOA takes payments (ED-43). [methods] holds only the accepted methods. [suspended] is
 * the administrator's hold; [outageSince] is set while payments are paused automatically because
 * the provider cannot be reached, and clears itself once it answers again (ED-46).
 */
data class PaymentSettings(
    val hoaId: UUID,
    val provider: String,
    val merchantRef: String,
    val suspended: Boolean,
    val methods: Map<PaymentMethod, FeeRule>,
    val outageSince: Instant? = null,
)

/** Whether the owners of an HOA can pay online right now (ED-46). */
enum class OnlinePayments(val code: String) {
    AVAILABLE("available"),

    /** Paused: an administrator's hold or a provider outage. History stays readable. */
    UNAVAILABLE("unavailable"),

    /** The HOA does not take online payments. */
    NOT_OFFERED("not_offered"),
}

enum class PaymentStatus(val code: String) {
    CREATED("created"),
    PROCESSING("processing"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELED("canceled"),
    ;

    /**
     * Valid state changes (GA-17). A failed attempt may still be retried by the owner at the
     * provider, so it can move on; succeeded and canceled are final (refunds and returns are
     * linked entries, never a state change back).
     */
    fun canBecome(next: PaymentStatus): Boolean = next != this && when (this) {
        CREATED -> next != CREATED
        PROCESSING -> next in setOf(SUCCEEDED, FAILED, CANCELED)
        FAILED -> next in setOf(PROCESSING, SUCCEEDED, CANCELED)
        SUCCEEDED, CANCELED -> false
    }

    companion object {
        fun fromCode(code: String): PaymentStatus = entries.first { it.code == code }

        fun of(status: ProviderPaymentStatus): PaymentStatus? = when (status) {
            ProviderPaymentStatus.AWAITING_OWNER -> null
            ProviderPaymentStatus.PROCESSING -> PROCESSING
            ProviderPaymentStatus.SUCCEEDED -> SUCCEEDED
            ProviderPaymentStatus.FAILED -> FAILED
            ProviderPaymentStatus.CANCELED -> CANCELED
        }
    }
}

/** A payment as its owner and administrators see it. */
data class Payment(
    val id: UUID,
    val reference: String,
    val propertyId: UUID,
    val method: PaymentMethod,
    val duesCents: Long,
    val feeCents: Long,
    val totalCents: Long,
    val currency: String,
    val status: PaymentStatus,
    val failureReason: String?,
    val createdAt: Instant,
    val succeededAt: Instant?,
)

internal object PaymentStore {
    const val MIN_PAYMENT_CENTS = 100L
    const val MAX_PAYMENT_CENTS = 5_000_000L

    fun settings(tx: DSLContext, hoaId: UUID): PaymentSettings? {
        val row = tx.selectFrom(PAYMENT_SETTINGS).where(PAYMENT_SETTINGS.HOA_ID.eq(hoaId)).fetchOne() ?: return null
        val methods = tx.selectFrom(PAYMENT_METHOD_FEE).where(PAYMENT_METHOD_FEE.HOA_ID.eq(hoaId)).fetch()
            .associate { requireNotNull(PaymentMethod.fromCode(it.method)) to FeeRule(it.feeBasisPoints, it.feeFixedCents) }
        return PaymentSettings(hoaId, row.provider, row.merchantRef, row.suspended, methods, row.outageSince?.toInstant())
    }

    fun availability(tx: DSLContext, hoaId: UUID, providers: PaymentProviders): OnlinePayments {
        val settings = settings(tx, hoaId)
        return when {
            settings == null || providers[settings.provider] == null || settings.methods.isEmpty() -> OnlinePayments.NOT_OFFERED
            settings.suspended || settings.outageSince != null -> OnlinePayments.UNAVAILABLE
            else -> OnlinePayments.AVAILABLE
        }
    }

    fun toPayment(r: PaymentRecord) = Payment(
        id = requireNotNull(r.id),
        reference = r.reference,
        propertyId = r.propertyId,
        method = requireNotNull(PaymentMethod.fromCode(r.method)),
        duesCents = r.duesCents,
        feeCents = r.feeCents,
        totalCents = r.totalCents,
        currency = r.currency,
        status = PaymentStatus.fromCode(r.status),
        failureReason = r.failureReason,
        createdAt = r.createdAt.toInstant(),
        succeededAt = r.succeededAt?.toInstant(),
    )

    /**
     * Settled payments of one account as ledger-backed lines: each credits its dues only (GA-22);
     * refunds and disputes follow as their own lines (ED-45).
     */
    fun settledLines(tx: DSLContext, hoaId: UUID, accountId: UUID, zone: ZoneId): List<OwnerFinance.Row> =
        paymentLines(tx, hoaId, accountId, zone) + adjustmentLines(tx, hoaId, accountId, zone)

    private fun paymentLines(tx: DSLContext, hoaId: UUID, accountId: UUID, zone: ZoneId): List<OwnerFinance.Row> =
        tx.selectFrom(PAYMENT)
            .where(PAYMENT.HOA_ID.eq(hoaId)).and(PAYMENT.ACCOUNT_ID.eq(accountId)).and(PAYMENT.STATUS.eq(PaymentStatus.SUCCEEDED.code))
            .fetch {
                OwnerFinance.Row(
                    requireNotNull(it.id), it.reference, TransactionType.PAYMENT,
                    it.succeededAt.toInstant().atZone(zone).toLocalDate(), null,
                    description(requireNotNull(PaymentMethod.fromCode(it.method))), -it.duesCents,
                )
            }

    /**
     * What Seebrook holds under a reference the HOA's records quote (ED-44, ED-45): [received] is
     * false for a payment not received yet; [ledgerCents] is its effect on what the owner owes
     * (negative for a payment, positive for a refund or return).
     */
    data class Recorded(val accountId: UUID, val received: Boolean, val status: String, val ledgerCents: Long)

    /** Payments and their adjustments of one HOA by reference. */
    fun byReferences(tx: DSLContext, hoaId: UUID, references: Collection<String>): Map<String, Recorded> {
        if (references.isEmpty()) return emptyMap()
        val payments = tx.select(PAYMENT.REFERENCE, PAYMENT.ACCOUNT_ID, PAYMENT.STATUS, PAYMENT.DUES_CENTS).from(PAYMENT)
            .where(PAYMENT.HOA_ID.eq(hoaId)).and(PAYMENT.REFERENCE.`in`(references))
            .fetch().associate {
                it.value1() to Recorded(requireNotNull(it.value2()), it.value3() == PaymentStatus.SUCCEEDED.code, it.value3(), -it.value4())
            }
        val adjustments = tx.select(PAYMENT_ADJUSTMENT.REFERENCE, PAYMENT.ACCOUNT_ID, PAYMENT_ADJUSTMENT.KIND, PAYMENT_ADJUSTMENT.DUES_CENTS)
            .from(PAYMENT_ADJUSTMENT).join(PAYMENT).on(PAYMENT.ID.eq(PAYMENT_ADJUSTMENT.PAYMENT_ID))
            .where(PAYMENT_ADJUSTMENT.HOA_ID.eq(hoaId)).and(PAYMENT_ADJUSTMENT.REFERENCE.`in`(references))
            .fetch().associate { it.value1() to Recorded(requireNotNull(it.value2()), true, it.value3(), it.value4()) }
        return payments + adjustments
    }

    data class Adjustment(
        val id: UUID,
        val paymentId: UUID,
        val kind: AdjustmentKind,
        val providerRef: String,
        val reference: String,
        val amountCents: Long,
        val duesCents: Long,
        val createdAt: Instant,
    )

    private fun toAdjustment(r: cc.seebrook.db.generated.finance.tables.records.PaymentAdjustmentRecord) = Adjustment(
        requireNotNull(r.id), r.paymentId, requireNotNull(AdjustmentKind.fromCode(r.kind)), r.providerRef, r.reference,
        r.amountCents, r.duesCents, r.createdAt.toInstant(),
    )

    fun adjustments(tx: DSLContext, paymentId: UUID): List<Adjustment> =
        tx.selectFrom(PAYMENT_ADJUSTMENT).where(PAYMENT_ADJUSTMENT.PAYMENT_ID.eq(paymentId))
            .orderBy(PAYMENT_ADJUSTMENT.CREATED_AT, PAYMENT_ADJUSTMENT.ID).fetch(::toAdjustment)

    /** Adjustments that changed one account's balance, as history lines (ED-45). */
    fun adjustmentLines(tx: DSLContext, hoaId: UUID, accountId: UUID, zone: ZoneId): List<OwnerFinance.Row> =
        tx.select(PAYMENT_ADJUSTMENT.asterisk(), PAYMENT.REFERENCE).from(PAYMENT_ADJUSTMENT)
            .join(PAYMENT).on(PAYMENT.ID.eq(PAYMENT_ADJUSTMENT.PAYMENT_ID))
            .where(PAYMENT_ADJUSTMENT.HOA_ID.eq(hoaId)).and(PAYMENT.ACCOUNT_ID.eq(accountId)).and(PAYMENT_ADJUSTMENT.DUES_CENTS.ne(0L))
            .fetch { r ->
                val a = toAdjustment(r.into(PAYMENT_ADJUSTMENT))
                OwnerFinance.Row(
                    a.id, a.reference, TransactionType.ADJUSTMENT, a.createdAt.atZone(zone).toLocalDate(), null,
                    adjustmentDescription(a.kind, r.get(PAYMENT.REFERENCE)), a.duesCents,
                )
            }

    fun adjustmentDescription(kind: AdjustmentKind, paymentReference: String) = when (kind) {
        AdjustmentKind.REFUND -> "Refund of online payment $paymentReference"
        AdjustmentKind.REFUND_FAILED -> "Refund of $paymentReference did not go through"
        AdjustmentKind.DISPUTE_WITHDRAWN -> "Online payment $paymentReference returned or disputed"
        AdjustmentKind.DISPUTE_REINSTATED -> "Online payment $paymentReference reinstated"
    }

    /** Every account's adjustment total, for the ledger invariant. */
    fun adjustmentTotals(tx: DSLContext, hoaId: UUID): Map<UUID, Long> =
        tx.select(PAYMENT.ACCOUNT_ID, DSL.sum(PAYMENT_ADJUSTMENT.DUES_CENTS)).from(PAYMENT_ADJUSTMENT)
            .join(PAYMENT).on(PAYMENT.ID.eq(PAYMENT_ADJUSTMENT.PAYMENT_ID))
            .where(PAYMENT_ADJUSTMENT.HOA_ID.eq(hoaId))
            .groupBy(PAYMENT.ACCOUNT_ID)
            .fetch().associate { requireNotNull(it.value1()) to it.value2().toLong() }

    /** Dues of payments submitted but not settled yet: shown apart from the confirmed balance. */
    fun pendingCents(tx: DSLContext, hoaId: UUID, accountId: UUID): Long =
        tx.select(DSL.coalesce(DSL.sum(PAYMENT.DUES_CENTS), DSL.inline(java.math.BigDecimal.ZERO))).from(PAYMENT)
            .where(PAYMENT.HOA_ID.eq(hoaId)).and(PAYMENT.ACCOUNT_ID.eq(accountId)).and(PAYMENT.STATUS.eq(PaymentStatus.PROCESSING.code))
            .fetchSingle().value1().toLong()

    /**
     * Every account's total from online payments, for the ledger invariant: settled dues
     * (negative) plus their refunds, failed refunds and disputes (ED-45).
     */
    fun settledTotals(tx: DSLContext, hoaId: UUID): Map<UUID, Long> {
        val paid = tx.select(PAYMENT.ACCOUNT_ID, DSL.sum(PAYMENT.DUES_CENTS)).from(PAYMENT)
            .where(PAYMENT.HOA_ID.eq(hoaId)).and(PAYMENT.STATUS.eq(PaymentStatus.SUCCEEDED.code))
            .groupBy(PAYMENT.ACCOUNT_ID)
            .fetch().associate { requireNotNull(it.value1()) to -it.value2().toLong() }
        val adjusted = adjustmentTotals(tx, hoaId)
        return (paid.keys + adjusted.keys).associateWith { (paid[it] ?: 0L) + (adjusted[it] ?: 0L) }
    }

    fun description(method: PaymentMethod) = when (method) {
        PaymentMethod.CARD -> "Online payment (card)"
        PaymentMethod.ACH -> "Online payment (bank account)"
    }

    private val random = SecureRandom()
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford: no I, L, O, U

    /** A short reference owners and the bookkeeper can quote, e.g. `PAY-7KQ2M9XD`; unique in the HOA. */
    fun newReference(tx: DSLContext, hoaId: UUID): String {
        while (true) {
            val ref = "PAY-" + (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            val taken = tx.fetchExists(PAYMENT, PAYMENT.HOA_ID.eq(hoaId).and(PAYMENT.REFERENCE.eq(ref)))
            if (!taken) return ref
        }
    }
}

/** Sets which provider, merchant account and methods an HOA uses, and each method's fee (audited). */
class ConfigurePayments(private val db: Database, private val providers: PaymentProviders) {
    data class Change(
        val provider: String,
        val merchantRef: String,
        val methods: Map<PaymentMethod, FeeRule>,
        val suspended: Boolean,
    )

    sealed interface Result {
        data class Planned(val before: PaymentSettings?, val after: PaymentSettings, val committed: Boolean) : Result
        data class Invalid(val message: String) : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, change: Change, commit: Boolean, reason: String?): Result {
        if (!actor.can(Permission.MANAGE_FINANCES, hoaId)) return Result.Forbidden
        if (change.provider !in providers.names) {
            return Result.Invalid("Payment provider '${change.provider}' is not available here (available: ${providers.names.sorted().joinToString().ifEmpty { "none" }})")
        }
        if (change.provider == StripePaymentProvider.NAME && !StripePaymentProvider.isConnectedAccount(change.merchantRef)) {
            return Result.Invalid("A Stripe merchant must be the HOA's connected account ID (acct_…)")
        }
        if (change.merchantRef.isBlank() || change.merchantRef.length > 255) return Result.Invalid("Merchant reference is required")
        if (change.methods.isEmpty()) return Result.Invalid("Accept at least one payment method")
        if (commit && reason.isNullOrBlank()) return Result.Invalid("A reason is required")

        return db.transaction { tx ->
            val before = PaymentStore.settings(tx, hoaId)
            val after = PaymentSettings(hoaId, change.provider, change.merchantRef.trim(), change.suspended, change.methods)
            val usedElsewhere = tx.fetchExists(
                PAYMENT_SETTINGS,
                PAYMENT_SETTINGS.PROVIDER.eq(after.provider).and(PAYMENT_SETTINGS.MERCHANT_REF.eq(after.merchantRef)).and(PAYMENT_SETTINGS.HOA_ID.ne(hoaId)),
            )
            if (usedElsewhere) return@transaction Result.Invalid("That merchant account already belongs to another HOA")
            if (!commit) return@transaction Result.Planned(before, after, committed = false)

            tx.insertInto(PAYMENT_SETTINGS)
                .set(PAYMENT_SETTINGS.HOA_ID, hoaId)
                .set(PAYMENT_SETTINGS.PROVIDER, after.provider)
                .set(PAYMENT_SETTINGS.MERCHANT_REF, after.merchantRef)
                .set(PAYMENT_SETTINGS.SUSPENDED, after.suspended)
                .onConflict(PAYMENT_SETTINGS.HOA_ID).doUpdate()
                .set(PAYMENT_SETTINGS.PROVIDER, after.provider)
                .set(PAYMENT_SETTINGS.MERCHANT_REF, after.merchantRef)
                .set(PAYMENT_SETTINGS.SUSPENDED, after.suspended)
                .set(PAYMENT_SETTINGS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
            tx.deleteFrom(PAYMENT_METHOD_FEE).where(PAYMENT_METHOD_FEE.HOA_ID.eq(hoaId)).execute()
            after.methods.forEach { (method, fee) ->
                tx.insertInto(PAYMENT_METHOD_FEE)
                    .set(PAYMENT_METHOD_FEE.HOA_ID, hoaId)
                    .set(PAYMENT_METHOD_FEE.METHOD, method.code)
                    .set(PAYMENT_METHOD_FEE.FEE_BASIS_POINTS, fee.basisPoints)
                    .set(PAYMENT_METHOD_FEE.FEE_FIXED_CENTS, fee.fixedCents)
                    .execute()
            }
            fun describe(s: PaymentSettings?) = buildJsonObject {
                if (s == null) return@buildJsonObject
                put("provider", s.provider)
                put("merchant", s.merchantRef)
                put("suspended", s.suspended)
                s.methods.toSortedMap().forEach { (m, f) -> put("${m.code}Fee", "${f.basisPoints}bp+${f.fixedCents}c") }
            }
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "finance.payment_settings_changed",
                    hoaId = hoaId,
                    targetType = "hoa",
                    targetId = hoaId,
                    reason = reason,
                    details = buildJsonObject {
                        put("before", describe(before))
                        put("after", describe(after))
                    },
                ),
            )
            Result.Planned(before, after, committed = true)
        }
    }
}

/** One choice the owner can approve: dues + this method's fee = total (GA-23). */
data class QuoteOption(val method: PaymentMethod, val feeCents: Long, val totalCents: Long)

data class PaymentQuote(val duesCents: Long, val amountDueCents: Long, val options: List<QuoteOption>)

internal sealed interface PaymentAccess {
    data class Ready(val context: OwnerFinance.Context, val settings: PaymentSettings) : PaymentAccess
    data object NotFound : PaymentAccess
    data object NoAccount : PaymentAccess
    data object Unavailable : PaymentAccess
}

/** An active owner of the property, with an account, at an HOA that takes payments right now. */
internal fun paymentAccess(tx: DSLContext, actor: UserActor, propertyId: UUID, providers: PaymentProviders): PaymentAccess {
    val context = when (val access = OwnerFinance.access(tx, actor, propertyId)) {
        is OwnerFinance.Access.Granted -> access.context
        OwnerFinance.Access.NotFound -> return PaymentAccess.NotFound
        OwnerFinance.Access.NoAccount -> return PaymentAccess.NoAccount
    }
    if (PaymentStore.availability(tx, context.period.hoaId, providers) != OnlinePayments.AVAILABLE) return PaymentAccess.Unavailable
    return PaymentAccess.Ready(context, requireNotNull(PaymentStore.settings(tx, context.period.hoaId)))
}

/**
 * The exact fee and total for each accepted method, before the owner pays (GA-23). Any amount from
 * $1 is allowed: partial and advance payments too (REQUIREMENTS).
 */
class QuotePayment(private val db: Database, private val providers: PaymentProviders) {
    sealed interface Result {
        data class Quoted(val quote: PaymentQuote) : Result
        data object NotFound : Result
        data object NoAccount : Result
        data object Unavailable : Result
        data object InvalidAmount : Result
    }

    operator fun invoke(actor: UserActor, propertyId: UUID, duesCents: Long): Result {
        if (duesCents !in PaymentStore.MIN_PAYMENT_CENTS..PaymentStore.MAX_PAYMENT_CENTS) return Result.InvalidAmount
        return db.transaction { tx ->
            when (val access = paymentAccess(tx, actor, propertyId, providers)) {
                is PaymentAccess.Ready -> Result.Quoted(quote(tx, access, duesCents))
                PaymentAccess.NotFound -> Result.NotFound
                PaymentAccess.NoAccount -> Result.NoAccount
                PaymentAccess.Unavailable -> Result.Unavailable
            }
        }
    }

    internal companion object {
        fun quote(tx: DSLContext, access: PaymentAccess.Ready, duesCents: Long): PaymentQuote {
            val balance = OwnerFinance.balance(tx, access.context)
            val options = access.settings.methods.toSortedMap().map { (method, fee) ->
                val feeCents = fee.feeFor(duesCents)
                QuoteOption(method, feeCents, duesCents + feeCents)
            }
            return PaymentQuote(duesCents, maxOf(balance, 0), options)
        }
    }
}

/**
 * Starts a payment for exactly what the owner approved (GA-23): the fee and total are recomputed
 * and, if they differ from what the owner saw, nothing is charged and the new quote is returned
 * for reconfirmation; the charge is never raised silently. The owner's idempotency key makes a
 * repeated submission return the same payment (never a second one). The provider's payment is
 * created with a key derived from Seebrook's payment, so a retry after a provider outage resumes
 * it. Nothing reaches the ledger here: only a confirmed settlement does (HandlePaymentEvent).
 */
class InitiatePayment(private val db: Database, private val providers: PaymentProviders) {
    data class Request(
        val method: PaymentMethod,
        val duesCents: Long,
        val feeCents: Long,
        val totalCents: Long,
        val idempotencyKey: String,
    )

    sealed interface Result {
        /** [clientParams] is empty once the payment has moved past waiting for the owner. */
        data class Started(val payment: Payment, val provider: String, val clientParams: Map<String, String>) : Result
        data class QuoteChanged(val quote: PaymentQuote) : Result
        data object IdempotencyConflict : Result
        data object NotFound : Result
        data object NoAccount : Result
        data object Unavailable : Result
        data object MethodNotAccepted : Result
        data class Invalid(val message: String) : Result
        data object ProviderUnavailable : Result
        data object ProviderRejected : Result
    }

    private val log = LoggerFactory.getLogger(InitiatePayment::class.java)

    operator fun invoke(actor: UserActor, propertyId: UUID, request: Request): Result {
        if (!IDEMPOTENCY_KEY.matches(request.idempotencyKey)) return Result.Invalid("idempotencyKey must be 8–100 letters, digits, '-' or '_'")
        if (request.duesCents !in PaymentStore.MIN_PAYMENT_CENTS..PaymentStore.MAX_PAYMENT_CENTS) return Result.Invalid("amount is out of range")

        val prepared = db.transaction { tx ->
            val existing = tx.selectFrom(PAYMENT)
                .where(PAYMENT.USER_ID.eq(actor.userId)).and(PAYMENT.IDEMPOTENCY_KEY.eq(request.idempotencyKey))
                .fetchOne()
            val access = when (val a = paymentAccess(tx, actor, propertyId, providers)) {
                is PaymentAccess.Ready -> a
                PaymentAccess.NotFound -> return@transaction Result.NotFound
                PaymentAccess.NoAccount -> return@transaction Result.NoAccount
                PaymentAccess.Unavailable -> return@transaction Result.Unavailable
            }
            if (existing != null) {
                val same = existing.propertyId == propertyId && existing.method == request.method.code &&
                    existing.duesCents == request.duesCents && existing.feeCents == request.feeCents && existing.totalCents == request.totalCents
                return@transaction if (same) existing else Result.IdempotencyConflict
            }
            val fee = access.settings.methods[request.method] ?: return@transaction Result.MethodNotAccepted
            val feeCents = fee.feeFor(request.duesCents)
            if (request.feeCents != feeCents || request.totalCents != request.duesCents + feeCents) {
                return@transaction Result.QuoteChanged(QuotePayment.quote(tx, access, request.duesCents))
            }
            val hoaId = access.context.period.hoaId
            val record = tx.insertInto(PAYMENT)
                .set(PAYMENT.HOA_ID, hoaId)
                .set(PAYMENT.ACCOUNT_ID, access.context.accountId)
                .set(PAYMENT.PROPERTY_ID, propertyId)
                .set(PAYMENT.USER_ID, actor.userId)
                .set(PAYMENT.REFERENCE, PaymentStore.newReference(tx, hoaId))
                .set(PAYMENT.PROVIDER, access.settings.provider)
                .set(PAYMENT.MERCHANT_REF, access.settings.merchantRef)
                .set(PAYMENT.METHOD, request.method.code)
                .set(PAYMENT.DUES_CENTS, request.duesCents)
                .set(PAYMENT.FEE_CENTS, feeCents)
                .set(PAYMENT.TOTAL_CENTS, request.duesCents + feeCents)
                .set(PAYMENT.IDEMPOTENCY_KEY, request.idempotencyKey)
                .returning()
                .fetchSingle()
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "finance.payment_started",
                    hoaId = hoaId,
                    targetType = "payment",
                    targetId = record.id,
                    details = buildJsonObject {
                        put("reference", record.reference)
                        put("method", record.method)
                        put("provider", record.provider)
                    },
                ),
            )
            record
        }
        val record = when (prepared) {
            is PaymentRecord -> prepared
            is Result -> return prepared
            else -> error("unexpected $prepared")
        }
        val payment = PaymentStore.toPayment(record)
        if (payment.status != PaymentStatus.CREATED) return Result.Started(payment, record.provider, emptyMap())

        // The provider is called outside any database transaction. Its idempotency key is
        // Seebrook's payment ID, so calling again for the same payment returns the same result.
        val provider = providers[record.provider] ?: return Result.Unavailable
        val created = try {
            provider.createPayment(
                CreatePaymentRequest(
                    merchantRef = record.merchantRef,
                    amountCents = record.totalCents,
                    currency = record.currency,
                    method = payment.method,
                    idempotencyKey = "seebrook-payment-${record.id}",
                    description = "HOA dues ${record.reference}",
                    metadata = mapOf(
                        StripePaymentProvider.METADATA_PAYMENT_ID to record.id.toString(),
                        "seebrook_reference" to record.reference,
                        "seebrook_hoa_id" to record.hoaId.toString(),
                        "seebrook_property_id" to record.propertyId.toString(),
                    ),
                ),
            )
        } catch (e: PaymentRequestRejectedException) {
            log.warn("Provider {} refused payment {}: {}", record.provider, record.id, e.message)
            return Result.ProviderRejected
        } catch (e: PaymentProviderException) {
            log.warn("Provider {} unavailable for payment {}: {}", record.provider, record.id, e.message)
            PaymentOutage.failed(db, record.hoaId, record.provider)
            return Result.ProviderUnavailable
        }
        PaymentOutage.answered(db, record.hoaId)
        db.transaction { tx ->
            val stored = tx.update(PAYMENT)
                .set(PAYMENT.PROVIDER_PAYMENT_ID, created.providerPaymentId)
                .set(PAYMENT.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(PAYMENT.ID.eq(record.id))
                .and(PAYMENT.PROVIDER_PAYMENT_ID.isNull.or(PAYMENT.PROVIDER_PAYMENT_ID.eq(created.providerPaymentId)))
                .execute()
            check(stored == 1) { "provider returned a different payment for the same idempotency key" }
        }
        return Result.Started(payment, record.provider, created.clientParams)
    }

    companion object {
        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9_-]{8,100}$")
    }
}

/**
 * Applies a provider webhook (GA-17): the signature and age are verified by the provider's
 * adapter; each event ID is applied once (a replay changes nothing); the event must match the
 * payment's provider, merchant account, amount and currency; and only valid state changes apply.
 * A payment that succeeds credits the ledger with its dues, once (GA-21, GA-22). Everything happens
 * in one transaction, so a failure leaves nothing applied and the provider's retry is safe.
 */
class HandlePaymentEvent(private val db: Database, private val providers: PaymentProviders) {
    enum class Outcome(val code: String) {
        APPLIED("applied"),
        UNCHANGED("unchanged"),
        IGNORED("ignored"),
        UNMATCHED("unmatched"),
        MISMATCH("mismatch"),
        DUPLICATE("duplicate"),
    }

    sealed interface Result {
        data class Accepted(val outcome: Outcome) : Result
        data class Rejected(val reason: String) : Result
        data object UnknownProvider : Result
    }

    private val log = LoggerFactory.getLogger(HandlePaymentEvent::class.java)

    operator fun invoke(providerName: String, payload: ByteArray, header: (String) -> String?): Result {
        val provider = providers[providerName] ?: return Result.UnknownProvider
        val event = when (val v = provider.verifyWebhook(payload, header)) {
            is WebhookVerification.Verified -> v.event
            is WebhookVerification.Rejected -> {
                log.warn("Rejected {} webhook: {}", providerName, v.reason)
                return Result.Rejected(v.reason)
            }
        }
        return Result.Accepted(record(providerName, event))
    }

    /**
     * Applies one verified event, once per event ID. Reconciliation (ED-46) passes what it looked
     * up at the provider through here too, so both paths share every check.
     */
    internal fun record(providerName: String, event: PaymentEvent): Outcome =
        db.transaction { tx ->
            // Concurrent deliveries of one event wait here, then see it as already received.
            tx.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "$providerName/${event.eventId}")
            if (tx.fetchExists(PAYMENT_EVENT, PAYMENT_EVENT.PROVIDER.eq(providerName).and(PAYMENT_EVENT.EVENT_ID.eq(event.eventId)))) {
                return@transaction Outcome.DUPLICATE
            }
            val (outcome, payment, detail) = when (event) {
                is PaymentEvent.Other -> Triple(Outcome.IGNORED, null, null)
                is PaymentEvent.PaymentUpdated -> apply(tx, providerName, event)
                is PaymentEvent.Adjusted -> adjust(tx, providerName, event)
            }
            tx.insertInto(PAYMENT_EVENT)
                .set(PAYMENT_EVENT.PROVIDER, providerName)
                .set(PAYMENT_EVENT.EVENT_ID, event.eventId.take(255))
                .set(PAYMENT_EVENT.EVENT_TYPE, event.type.take(64))
                .set(PAYMENT_EVENT.HOA_ID, payment?.hoaId)
                .set(PAYMENT_EVENT.PAYMENT_ID, payment?.id)
                .set(PAYMENT_EVENT.OUTCOME, outcome.code)
                .set(PAYMENT_EVENT.DETAIL, detail?.take(200))
                .execute()
            outcome
        }

    private fun apply(tx: DSLContext, providerName: String, event: PaymentEvent.PaymentUpdated): Triple<Outcome, PaymentRecord?, String?> {
        val byProviderId = tx.selectFrom(PAYMENT)
            .where(PAYMENT.PROVIDER.eq(providerName)).and(PAYMENT.MERCHANT_REF.eq(event.merchantRef))
            .and(PAYMENT.PROVIDER_PAYMENT_ID.eq(event.providerPaymentId))
            .forUpdate().fetchOne()
        // The provider's ID is stored right after it is created; an event can arrive first.
        val payment = byProviderId ?: event.seebrookPaymentId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { id ->
            tx.selectFrom(PAYMENT).where(PAYMENT.ID.eq(id)).and(PAYMENT.PROVIDER.eq(providerName)).and(PAYMENT.PROVIDER_PAYMENT_ID.isNull)
                .forUpdate().fetchOne()
        }
        if (payment == null) {
            log.warn("{} event {} matches no payment", providerName, event.eventId)
            return Triple(Outcome.UNMATCHED, null, "no payment for this provider payment")
        }
        // Merchant binding (GA-6, GA-17): the event must come from the account the payment was made on.
        if (payment.merchantRef != event.merchantRef) {
            log.error("{} event {} for payment {} came from a different merchant account", providerName, event.eventId, payment.id)
            return Triple(Outcome.MISMATCH, payment, "merchant account differs")
        }
        if (event.amountCents != payment.totalCents || !event.currency.equals(payment.currency, ignoreCase = true)) {
            log.error("{} event {} for payment {} has a different amount or currency", providerName, event.eventId, payment.id)
            return Triple(Outcome.MISMATCH, payment, "amount or currency differs")
        }
        if (payment.providerPaymentId == null) payment.providerPaymentId = event.providerPaymentId

        val current = PaymentStatus.fromCode(payment.status)
        val next = PaymentStatus.of(event.status)
        if (next == null || !current.canBecome(next)) {
            if (payment.changed()) payment.store()
            return Triple(Outcome.UNCHANGED, payment, "${current.code} -> ${next?.code ?: "awaiting owner"}")
        }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        payment.status = next.code
        payment.updatedAt = now
        payment.failureReason = if (next == PaymentStatus.FAILED) event.failureCode ?: "failed" else null
        if (next == PaymentStatus.SUCCEEDED) payment.succeededAt = now
        payment.store()
        if (next == PaymentStatus.SUCCEEDED) {
            // Only the dues reduce what the owner owes; the fee is the processor's (GA-22).
            tx.insertInto(LEDGER_ENTRY)
                .set(LEDGER_ENTRY.HOA_ID, payment.hoaId)
                .set(LEDGER_ENTRY.ACCOUNT_ID, payment.accountId)
                .set(LEDGER_ENTRY.AMOUNT_CENTS, -payment.duesCents)
                .set(LEDGER_ENTRY.KIND, "payment")
                .set(LEDGER_ENTRY.PAYMENT_ID, payment.id)
                .execute()
        }
        when (next) {
            PaymentStatus.SUCCEEDED -> PaymentEmailJob.enqueue(tx, payment.hoaId, requireNotNull(payment.id), PaymentEmailJob.Notice.RECEIVED)
            PaymentStatus.FAILED -> PaymentEmailJob.enqueue(tx, payment.hoaId, requireNotNull(payment.id), PaymentEmailJob.Notice.FAILED)
            else -> {}
        }
        Audit.record(
            tx, null, Channel.SYSTEM,
            AuditEntry(
                action = "finance.payment_${next.code}",
                hoaId = payment.hoaId,
                targetType = "payment",
                targetId = payment.id,
                details = buildJsonObject {
                    put("reference", payment.reference)
                    put("provider", providerName)
                    put("event", event.eventId)
                    put("from", current.code)
                    payment.failureReason?.let { put("failure", it) }
                },
            ),
        )
        return Triple(Outcome.APPLIED, payment, "${current.code} -> ${next.code}")
    }
}

/**
 * A refund, failed refund, dispute withdrawal or reinstatement of a received payment (GA-19,
 * ED-45). The payment itself never changes: each money movement is its own adjustment, once per
 * provider refund or dispute, with a linked compensating ledger entry for its dues part. Money
 * leaving the HOA reverses dues first (up to what is left of the payment's dues; any rest was
 * the fee); money coming back restores exactly what the matching refund or withdrawal reversed.
 */
private fun HandlePaymentEvent.adjust(tx: DSLContext, providerName: String, event: PaymentEvent.Adjusted): Triple<HandlePaymentEvent.Outcome, PaymentRecord?, String?> {
    val log = LoggerFactory.getLogger(HandlePaymentEvent::class.java)
    val payment = tx.selectFrom(PAYMENT)
        .where(PAYMENT.PROVIDER.eq(providerName)).and(PAYMENT.MERCHANT_REF.eq(event.merchantRef))
        .and(PAYMENT.PROVIDER_PAYMENT_ID.eq(event.providerPaymentId))
        .forUpdate().fetchOne()
    if (payment == null) {
        log.warn("{} {} event {} matches no payment", providerName, event.kind.code, event.eventId)
        return Triple(HandlePaymentEvent.Outcome.UNMATCHED, null, "no payment for this provider payment")
    }
    fun mismatch(detail: String): Triple<HandlePaymentEvent.Outcome, PaymentRecord?, String?> {
        log.error("{} {} event {} for payment {}: {}", providerName, event.kind.code, event.eventId, payment.id, detail)
        return Triple(HandlePaymentEvent.Outcome.MISMATCH, payment, detail)
    }
    if (!event.currency.equals(payment.currency, ignoreCase = true)) return mismatch("currency differs")
    if (payment.status != PaymentStatus.SUCCEEDED.code) return mismatch("payment was not received (${payment.status})")
    if (event.amountCents > payment.totalCents) return mismatch("amount is more than the payment")
    val adjustments = PaymentStore.adjustments(tx, requireNotNull(payment.id))
    if (adjustments.any { it.kind == event.kind && it.providerRef == event.providerRef }) {
        return Triple(HandlePaymentEvent.Outcome.UNCHANGED, payment, "${event.kind.code} already recorded")
    }
    val (duesCents, reference) = if (event.kind.restores) {
        val undone = if (event.kind == AdjustmentKind.REFUND_FAILED) AdjustmentKind.REFUND else AdjustmentKind.DISPUTE_WITHDRAWN
        val original = adjustments.firstOrNull { it.kind == undone && it.providerRef == event.providerRef }
            ?: return mismatch("no earlier ${undone.code} for ${event.providerRef.take(40)}")
        -original.duesCents to original.reference + (if (event.kind == AdjustmentKind.REFUND_FAILED) "F" else "W")
    } else {
        val reversed = adjustments.sumOf { it.duesCents }
        val letter = if (event.kind == AdjustmentKind.REFUND) "R" else "D"
        val n = adjustments.count { it.kind == event.kind } + 1
        minOf(event.amountCents, payment.duesCents - reversed).coerceAtLeast(0) to "${payment.reference}-$letter$n"
    }
    val adjustmentId = requireNotNull(
        tx.insertInto(PAYMENT_ADJUSTMENT)
            .set(PAYMENT_ADJUSTMENT.HOA_ID, payment.hoaId)
            .set(PAYMENT_ADJUSTMENT.PAYMENT_ID, payment.id)
            .set(PAYMENT_ADJUSTMENT.KIND, event.kind.code)
            .set(PAYMENT_ADJUSTMENT.PROVIDER_REF, event.providerRef.take(255))
            .set(PAYMENT_ADJUSTMENT.REFERENCE, reference)
            .set(PAYMENT_ADJUSTMENT.AMOUNT_CENTS, event.amountCents)
            .set(PAYMENT_ADJUSTMENT.DUES_CENTS, duesCents)
            .returning(PAYMENT_ADJUSTMENT.ID).fetchSingle().id,
    )
    if (duesCents != 0L) {
        tx.insertInto(LEDGER_ENTRY)
            .set(LEDGER_ENTRY.HOA_ID, payment.hoaId)
            .set(LEDGER_ENTRY.ACCOUNT_ID, payment.accountId)
            .set(LEDGER_ENTRY.AMOUNT_CENTS, duesCents)
            .set(LEDGER_ENTRY.KIND, if (duesCents > 0) "payment_reversal" else "payment_restored")
            .set(LEDGER_ENTRY.PAYMENT_ID, payment.id)
            .set(LEDGER_ENTRY.PAYMENT_ADJUSTMENT_ID, adjustmentId)
            .execute()
    }
    PaymentEmailJob.enqueue(tx, payment.hoaId, requireNotNull(payment.id), PaymentEmailJob.Notice.of(event.kind), adjustmentId)
    Audit.record(
        tx, null, Channel.SYSTEM,
        AuditEntry(
            action = "finance.payment_${event.kind.code}",
            hoaId = payment.hoaId,
            targetType = "payment",
            targetId = payment.id,
            details = buildJsonObject {
                put("reference", reference)
                put("provider", providerName)
                put("event", event.eventId)
            },
        ),
    )
    return Triple(HandlePaymentEvent.Outcome.APPLIED, payment, "${event.kind.code} $reference")
}

/**
 * The property's payments for its current owners, newest first: started, processing, failed and
 * received (REQUIREMENTS: home summary states). Payments made before the current owners' period
 * are not shown (ED-42).
 */
class ListPayments(private val db: Database) {
    sealed interface Result {
        data class Listed(val payments: List<Payment>) : Result
        data object NotFound : Result
        data object NoAccount : Result
    }

    operator fun invoke(actor: UserActor, propertyId: UUID, limit: Int = 50): Result = db.transaction { tx ->
        val context = when (val access = OwnerFinance.access(tx, actor, propertyId)) {
            is OwnerFinance.Access.Granted -> access.context
            OwnerFinance.Access.NotFound -> return@transaction Result.NotFound
            OwnerFinance.Access.NoAccount -> return@transaction Result.NoAccount
        }
        val since = context.period.previousOwnersLeftAt
        Result.Listed(
            tx.selectFrom(PAYMENT)
                .where(PAYMENT.HOA_ID.eq(context.period.hoaId)).and(PAYMENT.ACCOUNT_ID.eq(context.accountId))
                .and(if (since == null) DSL.noCondition() else PAYMENT.CREATED_AT.gt(since.atOffset(ZoneOffset.UTC)))
                .orderBy(PAYMENT.CREATED_AT.desc())
                .limit(limit)
                .fetch(PaymentStore::toPayment),
        )
    }
}

/** Administrators: an HOA's payments, newest first, optionally for one property. */
class ListHoaPayments(private val db: Database) {
    sealed interface Result {
        data class Listed(val payments: List<Payment>) : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, propertyId: UUID? = null, limit: Int = 200): Result {
        if (!actor.can(Permission.MANAGE_FINANCES, hoaId)) return Result.Forbidden
        return db.transaction { tx ->
            Result.Listed(
                tx.selectFrom(PAYMENT)
                    .where(PAYMENT.HOA_ID.eq(hoaId))
                    .and(if (propertyId == null) DSL.noCondition() else PAYMENT.PROPERTY_ID.eq(propertyId))
                    .orderBy(PAYMENT.CREATED_AT.desc())
                    .limit(limit)
                    .fetch(PaymentStore::toPayment),
            )
        }
    }
}

/** Reads payment settings for administrators. */
class GetPaymentSettings(private val db: Database) {
    operator fun invoke(actor: UserActor, hoaId: UUID): PaymentSettings? {
        require(actor.can(Permission.MANAGE_FINANCES, hoaId)) { "forbidden" }
        return db.transaction { tx -> PaymentStore.settings(tx, hoaId) }
    }
}
