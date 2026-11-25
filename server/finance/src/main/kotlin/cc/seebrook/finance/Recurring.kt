package cc.seebrook.finance

import cc.seebrook.db.generated.finance.Tables.PAYMENT
import cc.seebrook.db.generated.finance.Tables.PAYMENT_CUSTOMER
import cc.seebrook.db.generated.finance.Tables.PAYMENT_METHOD_SETUP
import cc.seebrook.db.generated.finance.Tables.RECURRING_CHARGE
import cc.seebrook.db.generated.finance.Tables.RECURRING_PLAN
import cc.seebrook.db.generated.finance.Tables.RECURRING_SCHEDULE
import cc.seebrook.db.generated.finance.Tables.SAVED_PAYMENT_METHOD
import cc.seebrook.db.generated.finance.tables.records.RecurringPlanRecord
import cc.seebrook.db.generated.finance.tables.records.SavedPaymentMethodRecord
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Channel
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.payments.CreateSetupRequest
import cc.seebrook.platform.payments.PaymentEvent
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProviderException
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.PaymentRequestRejectedException
import cc.seebrook.platform.payments.ProviderPaymentStatus
import cc.seebrook.platform.payments.SavedChargeRequest
import cc.seebrook.platform.payments.SetupStatus
import cc.seebrook.platform.payments.StripePaymentProvider
import cc.seebrook.property.Ownerships
import cc.seebrook.tenant.Hoas
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A payment method the provider keeps for an owner (ED-59); [label] is e.g. "Visa •••• 4242". */
data class SavedMethod(val id: UUID, val method: PaymentMethod, val label: String)

enum class PlanStatus(val code: String) {
    ACTIVE("active"),
    PAUSED("paused"),

    /** Charges wait until the owner approves the plan again (its fee changed, or its method is gone). */
    NEEDS_APPROVAL("needs_approval"),
    CANCELED("canceled"),
    ;

    companion object {
        fun fromCode(code: String) = entries.first { it.code == code }
    }
}

/** An owner's recurring payment for one property: a fixed amount they approved (GA-23). */
data class RecurringPlan(
    val id: UUID,
    val propertyId: UUID,
    val method: SavedMethod,
    val frequency: Frequency,
    val duesCents: Long,
    val feeCents: Long,
    val totalCents: Long,
    val status: PlanStatus,
    val statusReason: String?,
    /** The next due date, and the business day it is charged on; null once canceled. */
    val nextDueDate: LocalDate?,
    val nextChargeDate: LocalDate?,
)

/** One frequency an owner may choose, with when it would charge first. */
data class FrequencyOption(val frequency: Frequency, val firstDueDate: LocalDate, val firstChargeDate: LocalDate)

internal object RecurringStore {
    /** How long a due charge waits for paused payments before it is skipped. */
    val UNAVAILABLE_GRACE: Duration = Duration.ofDays(5)

    fun schedule(tx: DSLContext, hoaId: UUID): RecurringSchedule? =
        tx.selectFrom(RECURRING_SCHEDULE).where(RECURRING_SCHEDULE.HOA_ID.eq(hoaId)).fetchOne()?.let { r ->
            RecurringSchedule(
                hoaId, r.dayOfMonth.toInt(), r.anchorMonth.toInt(),
                buildSet {
                    if (r.monthly) add(Frequency.MONTHLY)
                    if (r.quarterly) add(Frequency.QUARTERLY)
                    if (r.annual) add(Frequency.ANNUAL)
                },
            )
        }

    fun toMethod(r: SavedPaymentMethodRecord) = SavedMethod(requireNotNull(r.id), requireNotNull(PaymentMethod.fromCode(r.method)), r.label)

    /** The owner's saved methods at this HOA's current merchant account. */
    fun methods(tx: DSLContext, hoaId: UUID, userId: UUID, settings: PaymentSettings): List<SavedMethod> =
        tx.selectFrom(SAVED_PAYMENT_METHOD)
            .where(SAVED_PAYMENT_METHOD.HOA_ID.eq(hoaId)).and(SAVED_PAYMENT_METHOD.USER_ID.eq(userId))
            .and(SAVED_PAYMENT_METHOD.PROVIDER.eq(settings.provider)).and(SAVED_PAYMENT_METHOD.MERCHANT_REF.eq(settings.merchantRef))
            .and(SAVED_PAYMENT_METHOD.REMOVED.isFalse)
            .orderBy(SAVED_PAYMENT_METHOD.CREATED_AT)
            .fetch(::toMethod)

    fun openPlan(tx: DSLContext, userId: UUID, propertyId: UUID, lock: Boolean = false): RecurringPlanRecord? =
        tx.selectFrom(RECURRING_PLAN)
            .where(RECURRING_PLAN.USER_ID.eq(userId)).and(RECURRING_PLAN.PROPERTY_ID.eq(propertyId))
            .and(RECURRING_PLAN.STATUS.ne(PlanStatus.CANCELED.code))
            .let { if (lock) it.forUpdate() else it }
            .fetchOne()

    fun toPlan(tx: DSLContext, r: RecurringPlanRecord): RecurringPlan {
        val method = toMethod(tx.selectFrom(SAVED_PAYMENT_METHOD).where(SAVED_PAYMENT_METHOD.ID.eq(r.savedMethodId)).fetchSingle())
        return RecurringPlan(
            id = requireNotNull(r.id),
            propertyId = r.propertyId,
            method = method,
            frequency = requireNotNull(Frequency.fromCode(r.frequency)),
            duesCents = r.duesCents,
            feeCents = r.feeCents,
            totalCents = r.totalCents,
            status = PlanStatus.fromCode(r.status),
            statusReason = r.statusReason,
            nextDueDate = r.nextDate,
            nextChargeDate = r.nextDate?.let(BankCalendar::chargeDay),
        )
    }

    /** The first due date of the plan's frequency not charged yet whose charge day is after [today]. */
    fun nextOpenDate(tx: DSLContext, schedule: RecurringSchedule, planId: UUID?, frequency: Frequency, today: LocalDate): LocalDate {
        var due = schedule.firstChargeableAfter(today, frequency)
        if (planId != null) {
            val charged = tx.select(RECURRING_CHARGE.SCHEDULED_DATE).from(RECURRING_CHARGE)
                .where(RECURRING_CHARGE.PLAN_ID.eq(planId)).and(RECURRING_CHARGE.SCHEDULED_DATE.ge(due))
                .fetchSet(RECURRING_CHARGE.SCHEDULED_DATE)
            while (due in charged) due = schedule.following(due, frequency)
        }
        return due
    }

    fun today(tx: DSLContext, hoaId: UUID, clock: Clock): LocalDate =
        LocalDate.now(clock.withZone(ZoneId.of(requireNotNull(Hoas.find(tx, hoaId)).timeZone)))

    fun planAudit(tx: DSLContext, actor: UserActor?, action: String, plan: RecurringPlanRecord, extra: Map<String, String> = emptyMap()) {
        Audit.record(
            tx, actor, actor?.channel ?: Channel.SYSTEM,
            AuditEntry(
                action = action,
                hoaId = plan.hoaId,
                targetType = "recurring_plan",
                targetId = plan.id,
                details = buildJsonObject {
                    put("property", plan.propertyId.toString())
                    put("frequency", plan.frequency)
                    put("dues_cents", plan.duesCents)
                    put("fee_cents", plan.feeCents)
                    put("status", plan.status)
                    plan.statusReason?.let { put("reason", it) }
                    plan.nextDate?.let { put("next_date", it.toString()) }
                    extra.forEach { (k, v) -> put(k, v) }
                },
            ),
        )
    }
}

/** Sets an HOA's recurring schedule (ED-59). Financial administrators and Root; audited. */
class ConfigureRecurringSchedule(private val db: Database) {
    sealed interface Result {
        data class Set(val before: RecurringSchedule?, val after: RecurringSchedule) : Result
        data class Invalid(val message: String) : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, dayOfMonth: Int, anchorMonth: Int, frequencies: Set<Frequency>, reason: String?): Result {
        if (!actor.can(Permission.MANAGE_FINANCES, hoaId)) return Result.Forbidden
        val why = reason?.trim()?.takeIf { it.isNotEmpty() } ?: return Result.Invalid("a reason is required")
        val after = runCatching { RecurringSchedule(hoaId, dayOfMonth, anchorMonth, frequencies) }
            .getOrElse { return Result.Invalid(it.message ?: "invalid schedule") }
        return db.transaction { tx ->
            val before = RecurringStore.schedule(tx, hoaId)
            tx.insertInto(RECURRING_SCHEDULE)
                .set(RECURRING_SCHEDULE.HOA_ID, hoaId)
                .set(RECURRING_SCHEDULE.DAY_OF_MONTH, dayOfMonth.toShort())
                .set(RECURRING_SCHEDULE.ANCHOR_MONTH, anchorMonth.toShort())
                .set(RECURRING_SCHEDULE.MONTHLY, Frequency.MONTHLY in frequencies)
                .set(RECURRING_SCHEDULE.QUARTERLY, Frequency.QUARTERLY in frequencies)
                .set(RECURRING_SCHEDULE.ANNUAL, Frequency.ANNUAL in frequencies)
                .onConflict(RECURRING_SCHEDULE.HOA_ID).doUpdate()
                .set(RECURRING_SCHEDULE.DAY_OF_MONTH, dayOfMonth.toShort())
                .set(RECURRING_SCHEDULE.ANCHOR_MONTH, anchorMonth.toShort())
                .set(RECURRING_SCHEDULE.MONTHLY, Frequency.MONTHLY in frequencies)
                .set(RECURRING_SCHEDULE.QUARTERLY, Frequency.QUARTERLY in frequencies)
                .set(RECURRING_SCHEDULE.ANNUAL, Frequency.ANNUAL in frequencies)
                .set(RECURRING_SCHEDULE.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "finance.recurring_schedule_set",
                    hoaId = hoaId,
                    targetType = "hoa",
                    targetId = hoaId,
                    reason = why,
                    details = buildJsonObject {
                        put("day_of_month", dayOfMonth)
                        put("anchor_month", anchorMonth)
                        put("frequencies", frequencies.sortedBy { it.months }.joinToString(",") { it.code })
                    },
                ),
            )
            Result.Set(before, after)
        }
    }
}

/** What an owner sees about recurring payments for one property (ED-59). */
class GetRecurring(private val db: Database, private val providers: PaymentProviders, private val clock: Clock = Clock.systemUTC()) {
    data class Overview(
        /** Empty when the HOA offers no recurring payments. */
        val options: List<FrequencyOption>,
        val methods: List<SavedMethod>,
        val plan: RecurringPlan?,
        /** Whether payment methods can be saved and plans set up right now. */
        val available: Boolean,
        val acceptedMethods: Set<PaymentMethod>,
    )

    sealed interface Result {
        data class Found(val overview: Overview) : Result
        data object NotFound : Result
        data object NoAccount : Result
    }

    operator fun invoke(actor: UserActor, propertyId: UUID): Result = db.transaction { tx ->
        val context = when (val access = OwnerFinance.access(tx, actor, propertyId)) {
            is OwnerFinance.Access.Granted -> access.context
            OwnerFinance.Access.NotFound -> return@transaction Result.NotFound
            OwnerFinance.Access.NoAccount -> return@transaction Result.NoAccount
        }
        val hoaId = context.period.hoaId
        val settings = PaymentStore.settings(tx, hoaId)
        val schedule = RecurringStore.schedule(tx, hoaId)
        val today = LocalDate.now(clock.withZone(context.zone))
        val plan = RecurringStore.openPlan(tx, actor.userId, propertyId)?.let { RecurringStore.toPlan(tx, it) }
        val offered = settings != null && schedule != null && PaymentStore.availability(tx, hoaId, providers) != OnlinePayments.NOT_OFFERED
        Result.Found(
            Overview(
                options = if (!offered) {
                    emptyList()
                } else {
                    schedule.frequencies.sortedBy { it.months }.map { f ->
                        val due = schedule.firstChargeableAfter(today, f)
                        FrequencyOption(f, due, BankCalendar.chargeDay(due))
                    }
                },
                methods = settings?.let { s -> RecurringStore.methods(tx, hoaId, actor.userId, s).filter { it.method in s.methods } }.orEmpty(),
                plan = plan,
                available = offered && PaymentStore.availability(tx, hoaId, providers) == OnlinePayments.AVAILABLE,
                acceptedMethods = settings?.methods?.keys.orEmpty(),
            ),
        )
    }
}

/**
 * Starts saving a payment method (ED-59): the owner's provider customer at this HOA (created the
 * first time), then a setup the app's provider SDK completes (X-02). Details go to the provider only.
 */
class StartMethodSetup(private val db: Database, private val providers: PaymentProviders) {
    private val log = LoggerFactory.getLogger(StartMethodSetup::class.java)

    sealed interface Result {
        class Started(val setupId: String, val provider: String, val clientParams: Map<String, String>) : Result {
            override fun toString() = "Started(setupId=$setupId, provider=$provider, clientParams=****)"
        }
        data object NotFound : Result
        data object NoAccount : Result
        data object Unavailable : Result
        data object NotOffered : Result
        data object MethodNotAccepted : Result
        data object ProviderUnavailable : Result
        data object ProviderRejected : Result
    }

    operator fun invoke(actor: UserActor, propertyId: UUID, method: PaymentMethod): Result {
        data class Prepared(val hoaId: UUID, val provider: String, val merchantRef: String, val customerRef: String?)
        val prepared = db.transaction { tx ->
            val access = when (val a = paymentAccess(tx, actor, propertyId, providers)) {
                is PaymentAccess.Ready -> a
                PaymentAccess.NotFound -> return@transaction Result.NotFound
                PaymentAccess.NoAccount -> return@transaction Result.NoAccount
                PaymentAccess.Unavailable -> return@transaction Result.Unavailable
            }
            val hoaId = access.context.period.hoaId
            if (RecurringStore.schedule(tx, hoaId) == null) return@transaction Result.NotOffered
            if (method !in access.settings.methods) return@transaction Result.MethodNotAccepted
            val customer = tx.select(PAYMENT_CUSTOMER.CUSTOMER_REF).from(PAYMENT_CUSTOMER)
                .where(PAYMENT_CUSTOMER.USER_ID.eq(actor.userId))
                .and(PAYMENT_CUSTOMER.PROVIDER.eq(access.settings.provider)).and(PAYMENT_CUSTOMER.MERCHANT_REF.eq(access.settings.merchantRef))
                .fetchOne()?.value1()
            Prepared(hoaId, access.settings.provider, access.settings.merchantRef, customer)
        }
        if (prepared is Result) return prepared
        prepared as Prepared
        val provider = providers[prepared.provider] ?: return Result.Unavailable
        val setup = try {
            provider.createSetup(
                CreateSetupRequest(
                    merchantRef = prepared.merchantRef,
                    method = method,
                    customerRef = prepared.customerRef,
                    // One customer per owner at this merchant account, whatever the race.
                    customerKey = actor.userId.toString(),
                    idempotencyKey = "seebrook-setup-${UUID.randomUUID()}",
                    metadata = mapOf("seebrook_user_id" to actor.userId.toString(), "seebrook_hoa_id" to prepared.hoaId.toString()),
                ),
            )
        } catch (e: PaymentRequestRejectedException) {
            log.warn("Provider {} refused a setup for HOA {}: {}", prepared.provider, prepared.hoaId, e.message)
            return Result.ProviderRejected
        } catch (e: PaymentProviderException) {
            log.warn("Provider {} unavailable for a setup for HOA {}: {}", prepared.provider, prepared.hoaId, e.message)
            PaymentOutage.failed(db, prepared.hoaId, prepared.provider)
            return Result.ProviderUnavailable
        }
        PaymentOutage.answered(db, prepared.hoaId)
        db.transaction { tx ->
            tx.insertInto(PAYMENT_CUSTOMER)
                .set(PAYMENT_CUSTOMER.HOA_ID, prepared.hoaId)
                .set(PAYMENT_CUSTOMER.USER_ID, actor.userId)
                .set(PAYMENT_CUSTOMER.PROVIDER, prepared.provider)
                .set(PAYMENT_CUSTOMER.MERCHANT_REF, prepared.merchantRef)
                .set(PAYMENT_CUSTOMER.CUSTOMER_REF, setup.customerRef)
                .onConflictDoNothing()
                .execute()
            tx.insertInto(PAYMENT_METHOD_SETUP)
                .set(PAYMENT_METHOD_SETUP.HOA_ID, prepared.hoaId)
                .set(PAYMENT_METHOD_SETUP.USER_ID, actor.userId)
                .set(PAYMENT_METHOD_SETUP.PROVIDER, prepared.provider)
                .set(PAYMENT_METHOD_SETUP.MERCHANT_REF, prepared.merchantRef)
                .set(PAYMENT_METHOD_SETUP.CUSTOMER_REF, setup.customerRef)
                .set(PAYMENT_METHOD_SETUP.METHOD, method.code)
                .set(PAYMENT_METHOD_SETUP.PROVIDER_SETUP_ID, setup.setupId)
                .execute()
        }
        return Result.Started(setup.setupId, prepared.provider, setup.clientParams)
    }
}

/**
 * Finishes saving a method once the app's SDK is done (ED-59). The server asks the provider itself
 * whether the setup succeeded; only the owner who started it can finish it.
 */
class CompleteMethodSetup(private val db: Database, private val providers: PaymentProviders) {
    sealed interface Result {
        data class Saved(val method: SavedMethod) : Result

        /** The provider is still verifying it (e.g. bank micro-deposits). */
        data object Pending : Result
        data object NotCompleted : Result
        data object NotFound : Result
        data object ProviderUnavailable : Result
    }

    operator fun invoke(actor: UserActor, propertyId: UUID, setupId: String): Result {
        val setup = db.transaction { tx ->
            val context = (OwnerFinance.access(tx, actor, propertyId) as? OwnerFinance.Access.Granted)?.context ?: return@transaction null
            tx.selectFrom(PAYMENT_METHOD_SETUP)
                .where(PAYMENT_METHOD_SETUP.PROVIDER_SETUP_ID.eq(setupId))
                .and(PAYMENT_METHOD_SETUP.USER_ID.eq(actor.userId))
                .and(PAYMENT_METHOD_SETUP.HOA_ID.eq(context.period.hoaId))
                .fetchOne()
        } ?: return Result.NotFound
        val provider = providers[setup.provider] ?: return Result.ProviderUnavailable
        val snapshot = try {
            provider.lookupSetup(setup.merchantRef, setup.providerSetupId)
        } catch (e: PaymentProviderException) {
            return Result.ProviderUnavailable
        }
        when (snapshot.status) {
            SetupStatus.PENDING -> return Result.Pending
            SetupStatus.NOT_COMPLETED -> return Result.NotCompleted
            SetupStatus.SUCCEEDED -> {}
        }
        val methodRef = snapshot.methodRef ?: return Result.NotCompleted
        // The saved method must be what the owner set out to save, for their own customer.
        if (snapshot.method?.code != setup.method || (snapshot.customerRef != null && snapshot.customerRef != setup.customerRef)) {
            return Result.NotCompleted
        }
        return db.transaction { tx ->
            val inserted = tx.insertInto(SAVED_PAYMENT_METHOD)
                .set(SAVED_PAYMENT_METHOD.HOA_ID, setup.hoaId)
                .set(SAVED_PAYMENT_METHOD.USER_ID, actor.userId)
                .set(SAVED_PAYMENT_METHOD.PROVIDER, setup.provider)
                .set(SAVED_PAYMENT_METHOD.MERCHANT_REF, setup.merchantRef)
                .set(SAVED_PAYMENT_METHOD.CUSTOMER_REF, setup.customerRef)
                .set(SAVED_PAYMENT_METHOD.METHOD_REF, methodRef)
                .set(SAVED_PAYMENT_METHOD.METHOD, setup.method)
                .set(SAVED_PAYMENT_METHOD.LABEL, snapshot.label ?: "Saved ${setup.method}")
                .onConflictDoNothing()
                .returning()
                .fetchOne()
            val record = inserted ?: tx.selectFrom(SAVED_PAYMENT_METHOD)
                .where(SAVED_PAYMENT_METHOD.PROVIDER.eq(setup.provider)).and(SAVED_PAYMENT_METHOD.MERCHANT_REF.eq(setup.merchantRef))
                .and(SAVED_PAYMENT_METHOD.METHOD_REF.eq(methodRef))
                .fetchSingle()
            if (record.userId != actor.userId) return@transaction Result.NotFound
            if (inserted != null) {
                Audit.record(
                    tx, actor,
                    AuditEntry(
                        action = "finance.payment_method_saved",
                        hoaId = setup.hoaId,
                        targetType = "saved_payment_method",
                        targetId = record.id,
                        details = buildJsonObject { put("method", setup.method) },
                    ),
                )
            }
            Result.Saved(RecurringStore.toMethod(record))
        }
    }
}

/** Removes a saved method (ED-59), unless an open plan still uses it. */
class RemoveSavedMethod(private val db: Database, private val providers: PaymentProviders) {
    sealed interface Result {
        data object Removed : Result
        data object InUse : Result
        data object NotFound : Result
        data object ProviderUnavailable : Result
    }

    operator fun invoke(actor: UserActor, propertyId: UUID, methodId: UUID): Result {
        val record = db.transaction { tx ->
            val context = (OwnerFinance.access(tx, actor, propertyId) as? OwnerFinance.Access.Granted)?.context ?: return@transaction null
            tx.selectFrom(SAVED_PAYMENT_METHOD)
                .where(SAVED_PAYMENT_METHOD.ID.eq(methodId)).and(SAVED_PAYMENT_METHOD.USER_ID.eq(actor.userId))
                .and(SAVED_PAYMENT_METHOD.HOA_ID.eq(context.period.hoaId)).and(SAVED_PAYMENT_METHOD.REMOVED.isFalse)
                .fetchOne()
        } ?: return Result.NotFound
        val inUse = db.dsl.fetchExists(
            RECURRING_PLAN,
            RECURRING_PLAN.SAVED_METHOD_ID.eq(methodId).and(RECURRING_PLAN.STATUS.ne(PlanStatus.CANCELED.code)),
        )
        if (inUse) return Result.InUse
        val provider = providers[record.provider] ?: return Result.ProviderUnavailable
        try {
            provider.removeSaved(record.merchantRef, record.methodRef)
        } catch (e: PaymentRequestRejectedException) {
            // Already gone at the provider: nothing left to remove there.
        } catch (e: PaymentProviderException) {
            return Result.ProviderUnavailable
        }
        return db.transaction { tx ->
            // A plan set up meanwhile keeps the method.
            val stillUnused = !tx.fetchExists(
                RECURRING_PLAN,
                RECURRING_PLAN.SAVED_METHOD_ID.eq(methodId).and(RECURRING_PLAN.STATUS.ne(PlanStatus.CANCELED.code)),
            )
            if (!stillUnused) return@transaction Result.InUse
            tx.update(SAVED_PAYMENT_METHOD)
                .set(SAVED_PAYMENT_METHOD.REMOVED, true)
                .set(SAVED_PAYMENT_METHOD.REMOVED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(SAVED_PAYMENT_METHOD.ID.eq(methodId)).and(SAVED_PAYMENT_METHOD.REMOVED.isFalse)
                .execute()
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "finance.payment_method_removed",
                    hoaId = record.hoaId,
                    targetType = "saved_payment_method",
                    targetId = methodId,
                    details = buildJsonObject { put("method", record.method) },
                ),
            )
            Result.Removed
        }
    }
}

/**
 * Sets up or changes the owner's recurring plan for a property (ED-59): a fixed amount the owner
 * approved, with its exact fee and total (GA-23). If the fee differs from what the owner saw,
 * nothing changes and the new figures are returned. Changing the plan approves it again, so a plan
 * waiting for approval becomes active. Charges start on the first due date after today.
 */
class SetUpRecurringPlan(private val db: Database, private val providers: PaymentProviders, private val clock: Clock = Clock.systemUTC()) {
    data class Request(
        val savedMethodId: UUID,
        val frequency: Frequency,
        val duesCents: Long,
        val feeCents: Long,
        val totalCents: Long,
    )

    sealed interface Result {
        data class Saved(val plan: RecurringPlan, val created: Boolean) : Result
        data class QuoteChanged(val feeCents: Long, val totalCents: Long) : Result
        data class Invalid(val message: String) : Result
        data object NotFound : Result
        data object NoAccount : Result
        data object Unavailable : Result
        data object NotOffered : Result
        data object MethodNotFound : Result
        data object MethodNotAccepted : Result
    }

    operator fun invoke(actor: UserActor, propertyId: UUID, request: Request): Result {
        if (request.duesCents !in PaymentStore.MIN_PAYMENT_CENTS..PaymentStore.MAX_PAYMENT_CENTS) return Result.Invalid("amount is out of range")
        return db.transaction { tx ->
            val access = when (val a = paymentAccess(tx, actor, propertyId, providers)) {
                is PaymentAccess.Ready -> a
                PaymentAccess.NotFound -> return@transaction Result.NotFound
                PaymentAccess.NoAccount -> return@transaction Result.NoAccount
                PaymentAccess.Unavailable -> return@transaction Result.Unavailable
            }
            val hoaId = access.context.period.hoaId
            val schedule = RecurringStore.schedule(tx, hoaId)
            if (schedule == null || request.frequency !in schedule.frequencies) return@transaction Result.NotOffered
            val method = tx.selectFrom(SAVED_PAYMENT_METHOD)
                .where(SAVED_PAYMENT_METHOD.ID.eq(request.savedMethodId)).and(SAVED_PAYMENT_METHOD.USER_ID.eq(actor.userId))
                .and(SAVED_PAYMENT_METHOD.HOA_ID.eq(hoaId)).and(SAVED_PAYMENT_METHOD.REMOVED.isFalse)
                .and(SAVED_PAYMENT_METHOD.PROVIDER.eq(access.settings.provider)).and(SAVED_PAYMENT_METHOD.MERCHANT_REF.eq(access.settings.merchantRef))
                .fetchOne() ?: return@transaction Result.MethodNotFound
            val kind = requireNotNull(PaymentMethod.fromCode(method.method))
            val fee = access.settings.methods[kind] ?: return@transaction Result.MethodNotAccepted
            val feeCents = fee.feeFor(request.duesCents)
            if (request.feeCents != feeCents || request.totalCents != request.duesCents + feeCents) {
                return@transaction Result.QuoteChanged(feeCents, request.duesCents + feeCents)
            }
            val today = LocalDate.now(clock.withZone(access.context.zone))
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val existing = RecurringStore.openPlan(tx, actor.userId, propertyId, lock = true)
            val nextDate = RecurringStore.nextOpenDate(tx, schedule, existing?.id, request.frequency, today)
            val record = existing?.apply {
                savedMethodId = requireNotNull(method.id)
                frequency = request.frequency.code
                duesCents = request.duesCents
                this.feeCents = feeCents
                totalCents = request.duesCents + feeCents
                status = PlanStatus.ACTIVE.code
                statusReason = null
                this.nextDate = nextDate
                updatedAt = now
                store()
            } ?: tx.insertInto(RECURRING_PLAN)
                .set(RECURRING_PLAN.HOA_ID, hoaId)
                .set(RECURRING_PLAN.ACCOUNT_ID, access.context.accountId)
                .set(RECURRING_PLAN.PROPERTY_ID, propertyId)
                .set(RECURRING_PLAN.USER_ID, actor.userId)
                .set(RECURRING_PLAN.SAVED_METHOD_ID, method.id)
                .set(RECURRING_PLAN.FREQUENCY, request.frequency.code)
                .set(RECURRING_PLAN.DUES_CENTS, request.duesCents)
                .set(RECURRING_PLAN.FEE_CENTS, feeCents)
                .set(RECURRING_PLAN.TOTAL_CENTS, request.duesCents + feeCents)
                .set(RECURRING_PLAN.NEXT_DATE, nextDate)
                .returning()
                .fetchSingle()
            RecurringStore.planAudit(tx, actor, if (existing == null) "finance.recurring_plan_created" else "finance.recurring_plan_changed", record)
            RecurringEmailJob.enqueue(tx, hoaId, requireNotNull(record.id), RecurringEmailJob.Notice.SET_UP, now)
            Result.Saved(RecurringStore.toPlan(tx, record), existing == null)
        }
    }
}

/** Pauses, resumes or cancels the owner's plan (ED-59). Works while payments are paused, too. */
class ChangeRecurringPlan(private val db: Database, private val clock: Clock = Clock.systemUTC()) {
    enum class Action { PAUSE, RESUME, CANCEL }

    sealed interface Result {
        data class Changed(val plan: RecurringPlan) : Result
        data object NoPlan : Result
        data object NotFound : Result

        /** Resuming a plan that waits for approval: the owner approves it by changing it. */
        data object NeedsApproval : Result
        data object Unchanged : Result
    }

    operator fun invoke(actor: UserActor, propertyId: UUID, action: Action): Result = db.transaction { tx ->
        val context = (OwnerFinance.access(tx, actor, propertyId) as? OwnerFinance.Access.Granted)?.context
            ?: return@transaction Result.NotFound
        val plan = RecurringStore.openPlan(tx, actor.userId, propertyId, lock = true) ?: return@transaction Result.NoPlan
        val status = PlanStatus.fromCode(plan.status)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        when (action) {
            Action.PAUSE -> {
                if (status != PlanStatus.ACTIVE) return@transaction Result.Unchanged
                plan.status = PlanStatus.PAUSED.code
            }
            Action.RESUME -> {
                if (status == PlanStatus.NEEDS_APPROVAL) return@transaction Result.NeedsApproval
                if (status != PlanStatus.PAUSED) return@transaction Result.Unchanged
                val schedule = RecurringStore.schedule(tx, context.period.hoaId) ?: return@transaction Result.NeedsApproval
                plan.status = PlanStatus.ACTIVE.code
                // Dates passed while paused are not charged later.
                plan.nextDate = RecurringStore.nextOpenDate(
                    tx, schedule, plan.id, requireNotNull(Frequency.fromCode(plan.frequency)), LocalDate.now(clock.withZone(context.zone)),
                )
            }
            Action.CANCEL -> cancel(tx, plan, "owner", now)
        }
        plan.updatedAt = now
        plan.store()
        if (action != Action.CANCEL) {
            RecurringStore.planAudit(tx, actor, "finance.recurring_plan_${action.name.lowercase()}d", plan)
        } else {
            RecurringStore.planAudit(tx, actor, "finance.recurring_plan_canceled", plan)
            RecurringEmailJob.enqueue(tx, plan.hoaId, requireNotNull(plan.id), RecurringEmailJob.Notice.CANCELED, now)
        }
        Result.Changed(RecurringStore.toPlan(tx, plan))
    }

    internal companion object {
        fun cancel(tx: DSLContext, plan: RecurringPlanRecord, reason: String, now: OffsetDateTime) {
            plan.status = PlanStatus.CANCELED.code
            plan.statusReason = reason
            plan.nextDate = null
            plan.canceledAt = now
            plan.updatedAt = now
        }
    }
}

/**
 * Cancels a departing owner's plans for a property when their ownership ends (GA-08). Queued in the
 * same transaction that ends the ownership, so it cannot be lost; running it again changes nothing.
 * The charge run also checks ownership before every charge.
 */
class CancelPlansForEndedOwnership(private val db: Database) {
    /** Plans canceled. */
    operator fun invoke(hoaId: UUID, propertyId: UUID, userId: UUID): Int = db.transaction { tx ->
        if (Ownerships.ownerPeriod(tx, propertyId, userId) != null) return@transaction 0
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val plans = tx.selectFrom(RECURRING_PLAN)
            .where(RECURRING_PLAN.HOA_ID.eq(hoaId)).and(RECURRING_PLAN.PROPERTY_ID.eq(propertyId))
            .and(RECURRING_PLAN.USER_ID.eq(userId)).and(RECURRING_PLAN.STATUS.ne(PlanStatus.CANCELED.code))
            .forUpdate().fetch()
        plans.forEach { plan ->
            ChangeRecurringPlan.cancel(tx, plan, "ownership_ended", now)
            plan.store()
            RecurringStore.planAudit(tx, null, "finance.recurring_plan_canceled", plan)
            RecurringEmailJob.enqueue(tx, hoaId, requireNotNull(plan.id), RecurringEmailJob.Notice.CANCELED, now)
        }
        plans.size
    }
}

/**
 * Charges recurring plans on their due dates (ED-59, GA-18). For each active plan whose charge day
 * has come, in one transaction: checks that the owner still owns the property, that the HOA takes
 * payments, that the saved method is still accepted and that the fee is still what the owner
 * approved; then records exactly one charge for that plan and date (started with a new payment, or
 * skipped with the reason) and moves the plan to its next date. The payment is then submitted to
 * the provider outside the transaction, with a key derived from it, so submitting again after a
 * crash or an outage cannot charge twice. A failed charge is never retried (REQUIREMENTS); the
 * owner is told and the plan goes on with its next date. Settlement comes from the provider's
 * verified events, as for every payment.
 */
class RunRecurringCharges(
    private val db: Database,
    private val providers: PaymentProviders,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(RunRecurringCharges::class.java)

    data class Summary(val started: Int, val skipped: Int, val waiting: Int, val canceled: Int, val submitted: Int)

    private enum class Outcome { STARTED, SKIPPED, WAITING, CANCELED, NOT_DUE }

    operator fun invoke(): Summary {
        // Every zone's today is at most one day after UTC's.
        val horizon = LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(1)
        val due = db.dsl.select(RECURRING_PLAN.ID).from(RECURRING_PLAN)
            .where(RECURRING_PLAN.STATUS.eq(PlanStatus.ACTIVE.code)).and(RECURRING_PLAN.NEXT_DATE.le(horizon))
            .orderBy(RECURRING_PLAN.NEXT_DATE, RECURRING_PLAN.ID)
            .fetch(RECURRING_PLAN.ID)
        val outcomes = due.map { decide(requireNotNull(it)) }
        val submitted = submitPending()
        return Summary(
            started = outcomes.count { it == Outcome.STARTED },
            skipped = outcomes.count { it == Outcome.SKIPPED },
            waiting = outcomes.count { it == Outcome.WAITING },
            canceled = outcomes.count { it == Outcome.CANCELED },
            submitted = submitted,
        )
    }

    private fun decide(planId: UUID): Outcome = db.transaction { tx ->
        // Another run holding this plan is deciding it already.
        val plan = tx.selectFrom(RECURRING_PLAN).where(RECURRING_PLAN.ID.eq(planId)).forUpdate().skipLocked().fetchOne()
            ?: return@transaction Outcome.NOT_DUE
        if (plan.status != PlanStatus.ACTIVE.code) return@transaction Outcome.NOT_DUE
        val dueDate = plan.nextDate ?: return@transaction Outcome.NOT_DUE
        val today = RecurringStore.today(tx, plan.hoaId, clock)
        val chargeDate = BankCalendar.chargeDay(dueDate)
        if (chargeDate.isAfter(today)) return@transaction Outcome.NOT_DUE
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val frequency = requireNotNull(Frequency.fromCode(plan.frequency))

        // The owner must still own the property; otherwise the plan ends (GA-08).
        if (Ownerships.ownerPeriod(tx, plan.propertyId, plan.userId) == null) {
            ChangeRecurringPlan.cancel(tx, plan, "ownership_ended", now)
            plan.store()
            RecurringStore.planAudit(tx, null, "finance.recurring_plan_canceled", plan)
            RecurringEmailJob.enqueue(tx, plan.hoaId, planId, RecurringEmailJob.Notice.CANCELED, now)
            return@transaction Outcome.CANCELED
        }

        fun advance() {
            val schedule = RecurringStore.schedule(tx, plan.hoaId)
            plan.nextDate = if (schedule != null && frequency in schedule.frequencies) {
                // Never back to a date already charged, even if the schedule changed.
                var next = schedule.firstDueAfter(dueDate, frequency)
                while (!BankCalendar.chargeDay(next).isAfter(today)) next = schedule.following(next, frequency)
                next
            } else {
                dueDate.plusMonths(frequency.months)
            }
            plan.updatedAt = now
        }

        fun skip(reason: String, waitForOwner: Boolean): Outcome {
            tx.insertInto(RECURRING_CHARGE)
                .set(RECURRING_CHARGE.HOA_ID, plan.hoaId)
                .set(RECURRING_CHARGE.PLAN_ID, planId)
                .set(RECURRING_CHARGE.SCHEDULED_DATE, dueDate)
                .set(RECURRING_CHARGE.CHARGE_DATE, chargeDate)
                .set(RECURRING_CHARGE.OUTCOME, "skipped")
                .set(RECURRING_CHARGE.SKIP_REASON, reason)
                .execute()
            advance()
            if (waitForOwner) {
                plan.status = PlanStatus.NEEDS_APPROVAL.code
                plan.statusReason = reason
            }
            plan.store()
            RecurringStore.planAudit(tx, null, "finance.recurring_charge_skipped", plan, mapOf("due_date" to dueDate.toString(), "skip_reason" to reason))
            RecurringEmailJob.enqueue(
                tx, plan.hoaId, planId,
                if (waitForOwner) RecurringEmailJob.Notice.NEEDS_APPROVAL else RecurringEmailJob.Notice.SKIPPED, now, dueDate,
            )
            return Outcome.SKIPPED
        }

        val settings = PaymentStore.settings(tx, plan.hoaId)
        when (PaymentStore.availability(tx, plan.hoaId, providers)) {
            OnlinePayments.NOT_OFFERED -> return@transaction skip("payments_not_offered", waitForOwner = false)
            OnlinePayments.UNAVAILABLE -> {
                // Paused (an outage or the administrator's hold): wait a few days, then skip this date.
                return@transaction if (today.isAfter(chargeDate.plusDays(RecurringStore.UNAVAILABLE_GRACE.toDays()))) {
                    skip("payments_unavailable", waitForOwner = false)
                } else {
                    Outcome.WAITING
                }
            }
            OnlinePayments.AVAILABLE -> {}
        }
        settings!!
        val method = tx.selectFrom(SAVED_PAYMENT_METHOD).where(SAVED_PAYMENT_METHOD.ID.eq(plan.savedMethodId)).fetchSingle()
        val kind = requireNotNull(PaymentMethod.fromCode(method.method))
        val fee = settings.methods[kind]
        if (method.removed || fee == null || method.provider != settings.provider || method.merchantRef != settings.merchantRef) {
            return@transaction skip("method_unavailable", waitForOwner = true)
        }
        // Never charge more than the owner approved (GA-23).
        if (fee.feeFor(plan.duesCents) != plan.feeCents) return@transaction skip("fee_changed", waitForOwner = true)

        val payment = tx.insertInto(PAYMENT)
            .set(PAYMENT.HOA_ID, plan.hoaId)
            .set(PAYMENT.ACCOUNT_ID, plan.accountId)
            .set(PAYMENT.PROPERTY_ID, plan.propertyId)
            .set(PAYMENT.USER_ID, plan.userId)
            .set(PAYMENT.REFERENCE, PaymentStore.newReference(tx, plan.hoaId))
            .set(PAYMENT.PROVIDER, settings.provider)
            .set(PAYMENT.MERCHANT_REF, settings.merchantRef)
            .set(PAYMENT.METHOD, kind.code)
            .set(PAYMENT.DUES_CENTS, plan.duesCents)
            .set(PAYMENT.FEE_CENTS, plan.feeCents)
            .set(PAYMENT.TOTAL_CENTS, plan.totalCents)
            .set(PAYMENT.IDEMPOTENCY_KEY, "recurring-$planId-$dueDate")
            .set(PAYMENT.RECURRING_PLAN_ID, planId)
            .returning()
            .fetchSingle()
        tx.insertInto(RECURRING_CHARGE)
            .set(RECURRING_CHARGE.HOA_ID, plan.hoaId)
            .set(RECURRING_CHARGE.PLAN_ID, planId)
            .set(RECURRING_CHARGE.SCHEDULED_DATE, dueDate)
            .set(RECURRING_CHARGE.CHARGE_DATE, chargeDate)
            .set(RECURRING_CHARGE.OUTCOME, "started")
            .set(RECURRING_CHARGE.PAYMENT_ID, payment.id)
            .execute()
        advance()
        plan.store()
        RecurringStore.planAudit(
            tx, null, "finance.recurring_charge_started", plan,
            mapOf("due_date" to dueDate.toString(), "payment" to payment.reference),
        )
        Outcome.STARTED
    }

    /**
     * Submits recurring payments not yet at the provider: those just started, and any left by a
     * crash or an outage. The key is derived from the payment, so the provider returns the same
     * charge if it already has it. Waits while the HOA's payments are paused.
     */
    private fun submitPending(): Int {
        val pending = db.dsl.select(PAYMENT.ID, PAYMENT.HOA_ID).from(PAYMENT)
            .where(PAYMENT.RECURRING_PLAN_ID.isNotNull).and(PAYMENT.STATUS.eq(PaymentStatus.CREATED.code))
            .and(PAYMENT.PROVIDER_PAYMENT_ID.isNull)
            .orderBy(PAYMENT.CREATED_AT)
            .fetch()
        var submitted = 0
        for (row in pending) {
            val hoaId = requireNotNull(row.value2())
            val available = db.transaction { tx -> PaymentStore.availability(tx, hoaId, providers) == OnlinePayments.AVAILABLE }
            if (!available) continue
            if (submit(requireNotNull(row.value1()))) submitted++
        }
        return submitted
    }

    private fun submit(paymentId: UUID): Boolean {
        val (payment, method) = db.transaction { tx ->
            val p = tx.selectFrom(PAYMENT).where(PAYMENT.ID.eq(paymentId)).fetchSingle()
            val plan = tx.selectFrom(RECURRING_PLAN).where(RECURRING_PLAN.ID.eq(p.recurringPlanId)).fetchSingle()
            p to tx.selectFrom(SAVED_PAYMENT_METHOD).where(SAVED_PAYMENT_METHOD.ID.eq(plan.savedMethodId)).fetchSingle()
        }
        val provider = providers[payment.provider] ?: return false
        val result = try {
            provider.chargeSaved(
                SavedChargeRequest(
                    merchantRef = payment.merchantRef,
                    customerRef = method.customerRef,
                    methodRef = method.methodRef,
                    method = requireNotNull(PaymentMethod.fromCode(payment.method)),
                    amountCents = payment.totalCents,
                    currency = payment.currency,
                    idempotencyKey = "seebrook-payment-${payment.id}",
                    description = "HOA dues ${payment.reference} (recurring)",
                    metadata = mapOf(
                        StripePaymentProvider.METADATA_PAYMENT_ID to payment.id.toString(),
                        "seebrook_reference" to payment.reference,
                        "seebrook_hoa_id" to payment.hoaId.toString(),
                        "seebrook_property_id" to payment.propertyId.toString(),
                    ),
                ),
            )
        } catch (e: PaymentRequestRejectedException) {
            // Never reached the provider as a charge: the owner is told it failed; it is not retried.
            log.warn("Provider {} refused recurring payment {}: {}", payment.provider, payment.id, e.message)
            failUnsent(paymentId, "payment_method_refused")
            return true
        } catch (e: PaymentProviderException) {
            log.warn("Provider {} unavailable for recurring payment {}: {}", payment.provider, payment.id, e.message)
            PaymentOutage.failed(db, payment.hoaId, payment.provider)
            return false
        }
        PaymentOutage.answered(db, payment.hoaId)
        db.transaction { tx ->
            tx.update(PAYMENT)
                .set(PAYMENT.PROVIDER_PAYMENT_ID, result.providerPaymentId)
                .set(PAYMENT.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(PAYMENT.ID.eq(paymentId))
                .and(PAYMENT.PROVIDER_PAYMENT_ID.isNull.or(PAYMENT.PROVIDER_PAYMENT_ID.eq(result.providerPaymentId)))
                .execute()
        }
        // A decline is known at once: apply it through the same checks as the provider's event,
        // which arrives later and then changes nothing.
        if (result.status == ProviderPaymentStatus.FAILED) {
            HandlePaymentEvent(db, providers).record(
                payment.provider,
                PaymentEvent.PaymentUpdated(
                    eventId = "seebrook-decline-${payment.id}",
                    type = "recurring.declined",
                    merchantRef = payment.merchantRef,
                    providerPaymentId = result.providerPaymentId,
                    status = ProviderPaymentStatus.FAILED,
                    amountCents = payment.totalCents,
                    currency = payment.currency,
                    seebrookPaymentId = payment.id.toString(),
                    failureCode = result.failureCode,
                ),
            )
        }
        return true
    }

    private fun failUnsent(paymentId: UUID, reason: String) = db.transaction { tx ->
        val updated = tx.update(PAYMENT)
            .set(PAYMENT.STATUS, PaymentStatus.FAILED.code)
            .set(PAYMENT.FAILURE_REASON, reason)
            .set(PAYMENT.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(PAYMENT.ID.eq(paymentId)).and(PAYMENT.STATUS.eq(PaymentStatus.CREATED.code)).and(PAYMENT.PROVIDER_PAYMENT_ID.isNull)
            .returning()
            .fetchOne() ?: return@transaction
        PaymentEmailJob.enqueue(tx, updated.hoaId, paymentId, PaymentEmailJob.Notice.FAILED)
        Audit.record(
            tx, null, Channel.SYSTEM,
            AuditEntry(
                action = "finance.payment_failed",
                hoaId = updated.hoaId,
                targetType = "payment",
                targetId = paymentId,
                details = buildJsonObject {
                    put("reference", updated.reference)
                    put("failure", reason)
                },
            ),
        )
    }
}

/** Administrators: an HOA's recurring plans (ED-59). */
class ListRecurringPlans(private val db: Database) {
    data class Row(val plan: RecurringPlan, val unitLabel: String)

    sealed interface Result {
        data class Listed(val rows: List<Row>) : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, includeCanceled: Boolean = false): Result {
        if (!actor.can(Permission.MANAGE_FINANCES, hoaId)) return Result.Forbidden
        return db.transaction { tx ->
            val units = cc.seebrook.property.Properties.byUnitLabel(tx, hoaId).entries.associate { (label, p) -> p.id to label }
            val plans = tx.selectFrom(RECURRING_PLAN).where(RECURRING_PLAN.HOA_ID.eq(hoaId))
                .let { if (includeCanceled) it else it.and(RECURRING_PLAN.STATUS.ne(PlanStatus.CANCELED.code)) }
                .orderBy(RECURRING_PLAN.CREATED_AT)
                .fetch()
            Result.Listed(plans.map { Row(RecurringStore.toPlan(tx, it), units[it.propertyId] ?: "?") })
        }
    }
}

/** Job handler for [cc.seebrook.property.OwnershipEndedJob]: cancels that owner's plans for the property. */
class OnOwnershipEnded(private val db: Database) : cc.seebrook.platform.jobs.JobHandler {
    override fun handle(job: cc.seebrook.platform.jobs.Job) {
        val hoaId = requireNotNull(job.hoaId)
        val propertyId = UUID.fromString(requireNotNull(job.payload["propertyId"]).toString().trim('"'))
        val userId = UUID.fromString(requireNotNull(job.payload["userId"]).toString().trim('"'))
        CancelPlansForEndedOwnership(db)(hoaId, propertyId, userId)
    }
}
