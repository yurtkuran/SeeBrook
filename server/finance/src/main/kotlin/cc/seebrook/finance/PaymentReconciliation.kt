package cc.seebrook.finance

import cc.seebrook.db.generated.finance.Tables.PAYMENT
import cc.seebrook.db.generated.finance.Tables.PAYMENT_SETTINGS
import cc.seebrook.platform.alerts.Alerts
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Channel
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.payments.PaymentEvent
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProviderException
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.PaymentRequestRejectedException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Automatic, HOA-isolated payment suspension while a provider cannot be reached (ED-46):
 * [THRESHOLD] calls in a row that the provider could not answer pause the HOA's payment actions;
 * only a successful availability check ([MonitorPayments]) resumes them. The administrator's hold
 * (`suspended`) is separate and never lifted automatically.
 */
internal object PaymentOutage {
    const val THRESHOLD = 3
    private val log = LoggerFactory.getLogger(PaymentOutage::class.java)

    fun outageKey(hoaId: UUID) = "payments_outage:$hoaId"

    /** Counts one unanswered call. True when this call started an outage. */
    fun failed(db: Database, hoaId: UUID, provider: String): Boolean = db.transaction { tx ->
        // The row lock serializes concurrent failures, so only one of them starts the outage.
        val row = tx.update(PAYMENT_SETTINGS)
            .set(PAYMENT_SETTINGS.PROVIDER_FAILURES, PAYMENT_SETTINGS.PROVIDER_FAILURES.plus(1))
            .where(PAYMENT_SETTINGS.HOA_ID.eq(hoaId))
            .returning(PAYMENT_SETTINGS.PROVIDER_FAILURES, PAYMENT_SETTINGS.OUTAGE_SINCE)
            .fetchOne() ?: return@transaction false
        if (row.outageSince != null || row.providerFailures < THRESHOLD) return@transaction false
        tx.update(PAYMENT_SETTINGS).set(PAYMENT_SETTINGS.OUTAGE_SINCE, DSL.currentOffsetDateTime())
            .where(PAYMENT_SETTINGS.HOA_ID.eq(hoaId)).execute()
        Alerts.raise(
            tx, "payments_outage", outageKey(hoaId), hoaId,
            "Online payments paused: $provider did not answer ${row.providerFailures} calls in a row",
            buildJsonObject {
                put("provider", provider)
                put("failures", row.providerFailures)
            },
        )
        Audit.record(
            tx, null, Channel.SYSTEM,
            AuditEntry(
                action = "finance.payments_outage_started",
                hoaId = hoaId,
                targetType = "hoa",
                targetId = hoaId,
                details = buildJsonObject {
                    put("provider", provider)
                    put("failures", row.providerFailures)
                },
            ),
        )
        true
    }

    /** The provider answered: the failure count starts again. Never ends an outage on its own. */
    fun answered(db: Database, hoaId: UUID) {
        db.transaction { tx ->
            tx.update(PAYMENT_SETTINGS).set(PAYMENT_SETTINGS.PROVIDER_FAILURES, 0)
                .where(PAYMENT_SETTINGS.HOA_ID.eq(hoaId))
                .and(PAYMENT_SETTINGS.PROVIDER_FAILURES.gt(0)).and(PAYMENT_SETTINGS.OUTAGE_SINCE.isNull)
                .execute()
        }
    }

    /** Ends an outage after a successful availability check. True if one was ended. */
    fun recovered(db: Database, hoaId: UUID, provider: String): Boolean = db.transaction { tx ->
        val since = tx.select(PAYMENT_SETTINGS.OUTAGE_SINCE).from(PAYMENT_SETTINGS)
            .where(PAYMENT_SETTINGS.HOA_ID.eq(hoaId)).forUpdate().fetchOne()?.value1() ?: return@transaction false
        tx.update(PAYMENT_SETTINGS)
            .set(PAYMENT_SETTINGS.OUTAGE_SINCE, null as OffsetDateTime?)
            .set(PAYMENT_SETTINGS.PROVIDER_FAILURES, 0)
            .where(PAYMENT_SETTINGS.HOA_ID.eq(hoaId)).execute()
        log.warn("Payments resumed for HOA {}: {} answers again (paused since {})", hoaId, provider, since)
        Alerts.clear(tx, outageKey(hoaId), "$provider answers again; online payments resumed")
        Audit.record(
            tx, null, Channel.SYSTEM,
            AuditEntry(
                action = "finance.payments_outage_ended",
                hoaId = hoaId,
                targetType = "hoa",
                targetId = hoaId,
                details = buildJsonObject {
                    put("provider", provider)
                    put("since", since.toInstant().toString())
                },
            ),
        )
        true
    }
}

/**
 * One pass of payment monitoring (ED-46), run every few minutes by the server and on demand by
 * `seebrook reconcile-payments`:
 * - an HOA paused by an outage is checked first and resumed if its provider answers; while it
 *   does not, nothing else is asked for that HOA;
 * - each unsettled payment (created or processing, or failed recently, since the owner may retry
 *   it at the provider) that has not changed for [settleAfter] is looked up at the provider, at
 *   most every [recheckEvery]; a different state is applied through [HandlePaymentEvent]'s own
 *   path, so a lost or delayed webhook is made good with the same checks and once-only effects.
 *   A difference that does not fit (another amount or currency) is kept as a mismatch for
 *   investigation and logged, never applied (GA-18).
 * Several servers may run it at once: events are applied once and payments are locked.
 */
class MonitorPayments(
    private val db: Database,
    private val providers: PaymentProviders,
    private val clock: Clock = Clock.systemUTC(),
    private val settleAfter: Duration = Duration.ofMinutes(15),
    private val recheckEvery: Duration = Duration.ofHours(1),
    private val failedWithin: Duration = Duration.ofDays(7),
    private val batchSize: Int = 200,
) {
    data class Summary(
        val checked: Int = 0,
        val applied: Int = 0,
        val mismatched: Int = 0,
        val providerErrors: Int = 0,
        val outagesStarted: Int = 0,
        val resumed: Int = 0,
        /** HOAs still paused by an outage after this pass. */
        val paused: Int = 0,
    ) {
        operator fun plus(o: Summary) = Summary(
            checked + o.checked, applied + o.applied, mismatched + o.mismatched, providerErrors + o.providerErrors,
            outagesStarted + o.outagesStarted, resumed + o.resumed, paused + o.paused,
        )
    }

    private val log = LoggerFactory.getLogger(MonitorPayments::class.java)
    private val events = HandlePaymentEvent(db, providers)

    operator fun invoke(): Summary {
        if (providers.names.isEmpty()) return Summary()
        val hoas = db.transaction { tx ->
            tx.select(PAYMENT_SETTINGS.HOA_ID).from(PAYMENT_SETTINGS)
                .where(PAYMENT_SETTINGS.PROVIDER.`in`(providers.names))
                .fetch { requireNotNull(it.value1()) }
        }
        val summary = hoas.fold(Summary()) { total, hoaId ->
            total + try {
                hoa(hoaId)
            } catch (e: Exception) {
                // One HOA's trouble never stops the others (type only: messages can hold personal data).
                log.error("Payment monitoring failed for HOA {} with {}", hoaId, e.javaClass.simpleName)
                Summary(providerErrors = 1)
            }
        }
        if (summary.applied + summary.mismatched + summary.outagesStarted + summary.resumed > 0) {
            log.info("Payment monitoring: {}", summary)
        }
        return summary
    }

    private fun hoa(hoaId: UUID): Summary {
        val settings = db.transaction { tx -> PaymentStore.settings(tx, hoaId) } ?: return Summary()
        var resumed = 0
        if (settings.outageSince != null) {
            val provider = providers[settings.provider] ?: return Summary(paused = 1)
            try {
                provider.checkAvailable(settings.merchantRef)
            } catch (e: PaymentProviderException) {
                return Summary(paused = 1)
            }
            if (PaymentOutage.recovered(db, hoaId, provider.name)) resumed = 1
        }

        val now = OffsetDateTime.now(clock)
        val due = db.transaction { tx ->
            tx.selectFrom(PAYMENT)
                .where(PAYMENT.HOA_ID.eq(hoaId))
                .and(PAYMENT.PROVIDER.`in`(providers.names))
                .and(PAYMENT.PROVIDER_PAYMENT_ID.isNotNull)
                .and(
                    PAYMENT.STATUS.`in`(PaymentStatus.CREATED.code, PaymentStatus.PROCESSING.code)
                        .or(PAYMENT.STATUS.eq(PaymentStatus.FAILED.code).and(PAYMENT.UPDATED_AT.gt(now.minus(failedWithin)))),
                )
                .and(PAYMENT.UPDATED_AT.lt(now.minus(settleAfter)))
                .and(PAYMENT.RECONCILED_AT.isNull.or(PAYMENT.RECONCILED_AT.lt(now.minus(recheckEvery))))
                .orderBy(PAYMENT.RECONCILED_AT.asc().nullsFirst(), PAYMENT.UPDATED_AT)
                .limit(batchSize)
                .fetch()
        }
        var result = Summary(resumed = resumed)
        var answered = false
        for (payment in due) {
            val provider = providers[payment.provider] ?: continue
            val snapshot = try {
                provider.lookupPayment(payment.merchantRef, payment.providerPaymentId)
            } catch (e: PaymentRequestRejectedException) {
                // The provider answered but refused (e.g. unknown payment): investigate, not an outage.
                log.error("{} refused to look up payment {}: {}", provider.name, payment.id, e.message)
                db.transaction { tx ->
                    Alerts.raise(
                        tx, "payment_lookup_refused", "payment_lookup_refused:${payment.id}", hoaId,
                        "${provider.name} refused to look up payment ${payment.reference}",
                        buildJsonObject {
                            put("payment", payment.reference)
                            put("provider payment", payment.providerPaymentId)
                        },
                    )
                }
                markReconciled(payment.id, now)
                result += Summary(checked = 1, providerErrors = 1)
                continue
            } catch (e: PaymentProviderException) {
                result += Summary(checked = 1, providerErrors = 1)
                if (PaymentOutage.failed(db, hoaId, provider.name)) return result + Summary(outagesStarted = 1, paused = 1)
                continue
            }
            answered = true
            markReconciled(payment.id, now)
            result += Summary(checked = 1)
            val next = PaymentStatus.of(snapshot.status)
            if (next == null || next.code == payment.status) continue
            val outcome = events.record(
                provider.name,
                PaymentEvent.PaymentUpdated(
                    // One event per state of the payment: a later change is looked up anew, a repeat is a no-op.
                    eventId = "seebrook-reconcile:${payment.id}:${payment.updatedAt.toInstant().toEpochMilli()}:${next.code}",
                    type = "reconcile.${next.code}",
                    merchantRef = payment.merchantRef,
                    providerPaymentId = snapshot.providerPaymentId,
                    status = snapshot.status,
                    amountCents = snapshot.amountCents,
                    currency = snapshot.currency,
                    seebrookPaymentId = null,
                    failureCode = snapshot.failureCode,
                ),
            )
            result += when (outcome) {
                HandlePaymentEvent.Outcome.APPLIED -> Summary(applied = 1)
                HandlePaymentEvent.Outcome.MISMATCH, HandlePaymentEvent.Outcome.UNMATCHED -> Summary(mismatched = 1)
                else -> Summary()
            }
        }
        if (answered) PaymentOutage.answered(db, hoaId)
        return result
    }

    private fun markReconciled(paymentId: UUID, now: OffsetDateTime) {
        db.transaction { tx -> tx.update(PAYMENT).set(PAYMENT.RECONCILED_AT, now).where(PAYMENT.ID.eq(paymentId)).execute() }
    }
}

/**
 * Payments stuck longer than they should be (ED-61), per HOA: card payments still processing after
 * [cardAfter], bank payments after [bankAfter] (bank debits take a few business days), and
 * recurring charges not yet sent to the provider after [unsentAfter]. Opens `payments_unsettled`
 * for the HOA while any is stuck and clears it once none is. Payments waiting for the owner to
 * finish checkout are not stuck: the owner may never finish.
 */
class CheckUnsettledPayments(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val cardAfter: Duration = Duration.ofDays(2),
    private val bankAfter: Duration = Duration.ofDays(10),
    private val unsentAfter: Duration = Duration.ofDays(1),
) {
    /** How many payments are stuck now. */
    operator fun invoke(): Int = db.transaction { tx ->
        val now = OffsetDateTime.now(clock)
        val processing = PAYMENT.STATUS.eq(PaymentStatus.PROCESSING.code)
        val stuck = tx.select(PAYMENT.HOA_ID, PAYMENT.REFERENCE).from(PAYMENT)
            .where(
                processing.and(PAYMENT.METHOD.eq(PaymentMethod.CARD.code)).and(PAYMENT.UPDATED_AT.lt(now.minus(cardAfter)))
                    .or(processing.and(PAYMENT.METHOD.ne(PaymentMethod.CARD.code)).and(PAYMENT.UPDATED_AT.lt(now.minus(bankAfter))))
                    .or(
                        PAYMENT.STATUS.eq(PaymentStatus.CREATED.code).and(PAYMENT.RECURRING_PLAN_ID.isNotNull)
                            .and(PAYMENT.PROVIDER_PAYMENT_ID.isNull).and(PAYMENT.CREATED_AT.lt(now.minus(unsentAfter))),
                    ),
            )
            .orderBy(PAYMENT.CREATED_AT)
            .fetchGroups({ requireNotNull(it.value1()) }, { it.value2() })
        val prefix = "payments_unsettled:"
        Alerts.openKeys(tx, prefix).forEach { key ->
            val hoaId = runCatching { UUID.fromString(key.removePrefix(prefix)) }.getOrNull()
            if (hoaId == null || hoaId !in stuck) Alerts.clear(tx, key, "no payments are stuck any more")
        }
        stuck.forEach { (hoaId, references) ->
            Alerts.raise(
                tx, "payments_unsettled", "$prefix$hoaId", hoaId,
                "${references.size} payment(s) not settled in the usual time",
                buildJsonObject {
                    put("payments", references.take(10).joinToString())
                    if (references.size > 10) put("and more", references.size - 10)
                },
            )
        }
        stuck.values.sumOf { it.size }
    }
}
