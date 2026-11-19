package cc.seebrook.finance

import cc.seebrook.db.generated.finance.Tables.ACCOUNT
import cc.seebrook.db.generated.finance.Tables.IMPORT_BATCH
import cc.seebrook.db.generated.finance.Tables.LEDGER_ENTRY
import cc.seebrook.db.generated.finance.Tables.TRANSACTION
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.property.OwnerPeriod
import cc.seebrook.property.Ownerships
import cc.seebrook.tenant.Hoas
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** One transaction as an owner sees it: current values only, never correction history. */
data class OwnerTransaction(
    val id: UUID,
    val reference: String,
    val type: TransactionType,
    val date: LocalDate,
    val dueDate: LocalDate?,
    val description: String,
    val amountCents: Long,
    /** The balance after this transaction, counting the carried-forward amount. */
    val balanceAfterCents: Long,
)

/**
 * Everything before the current owners' period, as one amount without details (ED-42): the
 * balance the unit carried when the previous owner left.
 */
data class CarriedForward(val amountCents: Long, val throughDate: LocalDate)

data class AccountSummary(
    /** Positive: owed; negative: a credit. */
    val balanceCents: Long,
    /** What the owner owes now: the balance when positive, else 0. */
    val amountDueCents: Long,
    /** The part of [amountDueCents] for charges past their due date. */
    val overdueCents: Long,
    val notYetDueCents: Long,
    /** The credit when the balance is negative, else 0. */
    val creditCents: Long,
    /** The earliest due date, today or later, of a charge not yet due. */
    val nextDueDate: LocalDate?,
    /** Today in the HOA's time zone: the day "overdue" is measured against. */
    val asOfDate: LocalDate,
    /** When the HOA's finance data was last imported; null before the first import. */
    val updatedAt: Instant?,
    /** Online payments submitted but not settled yet; not part of the balance until they settle. */
    val pendingPaymentsCents: Long = 0,
)

/**
 * Owner access to a property's finances (GA-20, ED-42). Only active owners of the property; an
 * unknown property and one the user does not own look the same (GA-06). Owners see the account's
 * full balance, but transactions only from their period: everything dated on or before the day
 * the previous owner left is one carried-forward amount.
 */
internal object OwnerFinance {
    data class Context(val period: OwnerPeriod, val accountId: UUID, val zone: ZoneId)

    sealed interface Access {
        data class Granted(val context: Context) : Access
        data object NotFound : Access
        data object NoAccount : Access
    }

    fun access(tx: DSLContext, actor: UserActor, propertyId: UUID): Access {
        val period = Ownerships.ownerPeriod(tx, propertyId, actor.userId) ?: return Access.NotFound
        val accountId = tx.select(ACCOUNT.ID).from(ACCOUNT)
            .where(ACCOUNT.HOA_ID.eq(period.hoaId)).and(ACCOUNT.PROPERTY_ID.eq(propertyId))
            .fetchOne()?.value1() ?: return Access.NoAccount
        val zone = ZoneId.of(requireNotNull(Hoas.find(tx, period.hoaId)).timeZone)
        return Access.Granted(Context(period, accountId, zone))
    }

    data class Row(val id: UUID, val reference: String, val type: TransactionType, val date: LocalDate, val dueDate: LocalDate?, val description: String, val amountCents: Long)

    /**
     * The account's transactions at their current values, voided ones (amount 0) left out, and its
     * settled online payments (ED-43), oldest first.
     */
    fun transactions(tx: DSLContext, context: Context): List<Row> =
        (imported(tx, context) + PaymentStore.settledLines(tx, context.period.hoaId, context.accountId, context.zone))
            .sortedWith(compareBy({ it.date }, { it.reference }))

    private fun imported(tx: DSLContext, context: Context): List<Row> =
        tx.selectFrom(TRANSACTION)
            .where(TRANSACTION.HOA_ID.eq(context.period.hoaId)).and(TRANSACTION.ACCOUNT_ID.eq(context.accountId))
            .and(TRANSACTION.AMOUNT_CENTS.ne(0L))
            .orderBy(TRANSACTION.TRANSACTION_DATE, TRANSACTION.EXTERNAL_ID)
            .fetch {
                Row(
                    requireNotNull(it.id), it.externalId, requireNotNull(TransactionType.fromCode(it.type)),
                    it.transactionDate, it.dueDate, it.description, it.amountCents,
                )
            }

    fun balance(tx: DSLContext, context: Context): Long =
        tx.select(DSL.coalesce(DSL.sum(LEDGER_ENTRY.AMOUNT_CENTS), DSL.inline(java.math.BigDecimal.ZERO))).from(LEDGER_ENTRY)
            .where(LEDGER_ENTRY.HOA_ID.eq(context.period.hoaId)).and(LEDGER_ENTRY.ACCOUNT_ID.eq(context.accountId))
            .fetchSingle().value1().toLong()

    /** The last day of the previous owner's period, in the HOA's time zone; null when there was none. */
    fun boundary(context: Context): LocalDate? = context.period.previousOwnersLeftAt?.atZone(context.zone)?.toLocalDate()
}

/** The selected property's balance with what is due and overdue (REQUIREMENTS: per-property summary). */
class GetAccountSummary(private val db: Database, private val clock: Clock = Clock.systemUTC()) {
    sealed interface Result {
        data class Summary(val summary: AccountSummary) : Result
        data object NotFound : Result
        data object NoAccount : Result
    }

    operator fun invoke(actor: UserActor, propertyId: UUID): Result = db.transaction { tx ->
        val context = when (val access = OwnerFinance.access(tx, actor, propertyId)) {
            is OwnerFinance.Access.Granted -> access.context
            OwnerFinance.Access.NotFound -> return@transaction Result.NotFound
            OwnerFinance.Access.NoAccount -> return@transaction Result.NoAccount
        }
        val today = LocalDate.now(clock.withZone(context.zone))
        val balance = OwnerFinance.balance(tx, context)
        val rows = OwnerFinance.transactions(tx, context)
        val updatedAt = tx.select(DSL.max(IMPORT_BATCH.IMPORTED_AT)).from(IMPORT_BATCH)
            .where(IMPORT_BATCH.HOA_ID.eq(context.period.hoaId)).fetchOne()?.value1()?.toInstant()
        val pending = PaymentStore.pendingCents(tx, context.period.hoaId, context.accountId)
        Result.Summary(
            DueStatus.of(balance, rows.map { Charge(it.amountCents, it.dueDate ?: it.date) }, today, updatedAt).copy(pendingPaymentsCents = pending),
        )
    }
}

/** An amount with the date it is due (for credits the date is not used). */
internal data class Charge(val amountCents: Long, val due: LocalDate)

/**
 * Due and overdue from the balance (ED-42): credits pay the oldest charges first, so the overdue
 * part is what charges already past due would leave unpaid after every credit, never more than the
 * balance. A charge without a due date is due on its transaction date; one due today is not overdue.
 */
internal object DueStatus {
    fun of(balanceCents: Long, items: List<Charge>, today: LocalDate, updatedAt: Instant?): AccountSummary {
        val owed = maxOf(balanceCents, 0)
        val credits = items.filter { it.amountCents < 0 }.sumOf { it.amountCents }
        val pastDue = items.filter { it.amountCents > 0 && it.due.isBefore(today) }.sumOf { it.amountCents }
        val overdue = (pastDue + credits).coerceIn(0, owed)
        val notYetDue = owed - overdue
        val nextDue = if (notYetDue > 0) items.filter { it.amountCents > 0 && !it.due.isBefore(today) }.minOfOrNull { it.due } else null
        return AccountSummary(balanceCents, owed, overdue, notYetDue, maxOf(-balanceCents, 0), nextDue, today, updatedAt)
    }
}

/**
 * The owner's transaction history for the selected property, newest first, with the carried-forward
 * amount when the unit had an earlier owner (ED-42). Pages default to 20 transactions, at most 50.
 */
class ListAccountTransactions(private val db: Database) {
    data class Page(
        val transactions: List<OwnerTransaction>,
        val carriedForward: CarriedForward?,
        val page: Int,
        val pageSize: Int,
        val total: Int,
    )

    sealed interface Result {
        data class Listed(val page: Page) : Result
        data object NotFound : Result
        data object NoAccount : Result
        data object InvalidQuery : Result
    }

    operator fun invoke(actor: UserActor, propertyId: UUID, page: Int = 1, pageSize: Int = DEFAULT_PAGE_SIZE): Result {
        if (page < 1 || pageSize !in 1..MAX_PAGE_SIZE) return Result.InvalidQuery
        return db.transaction { tx ->
            val context = when (val access = OwnerFinance.access(tx, actor, propertyId)) {
                is OwnerFinance.Access.Granted -> access.context
                OwnerFinance.Access.NotFound -> return@transaction Result.NotFound
                OwnerFinance.Access.NoAccount -> return@transaction Result.NoAccount
            }
            val boundary = OwnerFinance.boundary(context)
            val visible = OwnerFinance.transactions(tx, context).filter { boundary == null || it.date.isAfter(boundary) }
            // The carried-forward amount is derived from the ledger, so the visible lines always
            // add up to the balance the summary shows.
            val opening = OwnerFinance.balance(tx, context) - visible.sumOf { it.amountCents }
            var running = opening
            val withBalances = visible.map { r ->
                running += r.amountCents
                OwnerTransaction(r.id, r.reference, r.type, r.date, r.dueDate, r.description, r.amountCents, running)
            }.asReversed()
            Result.Listed(
                Page(
                    transactions = withBalances.drop((page - 1) * pageSize).take(pageSize),
                    carriedForward = boundary?.let { CarriedForward(opening, it) },
                    page = page,
                    pageSize = pageSize,
                    total = withBalances.size,
                ),
            )
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
    }
}
