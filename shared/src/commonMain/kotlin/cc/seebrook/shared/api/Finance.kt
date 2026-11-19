package cc.seebrook.shared.api

import kotlinx.serialization.Serializable

/**
 * The selected property's balance (ED-42). Amounts are integer cents in `currency`; positive is
 * owed, negative is a credit. Dates are ISO-8601: `nextDueDate` and `asOfDate` dates, `updatedAt`
 * an instant. Every figure is computed by the backend; clients only display them.
 */
@Serializable
data class AccountSummaryResponse(
    val propertyId: String,
    val currency: String,
    val balanceCents: Long,
    val amountDueCents: Long,
    val overdueCents: Long,
    val notYetDueCents: Long,
    val creditCents: Long,
    val nextDueDate: String? = null,
    /** Today in the HOA's time zone: what "overdue" is measured against. */
    val asOfDate: String,
    /** When the HOA's finance data was last imported. */
    val updatedAt: String? = null,
    /** Online payments submitted but not settled yet; the balance changes only once they settle. */
    val pendingPaymentsCents: Long,
)

/** `type` is one of assessment, late_fee, adjustment, waiver, payment. */
@Serializable
data class AccountTransactionResponse(
    val id: String,
    /** The transaction's number in the HOA's records, for questions to the HOA. */
    val reference: String,
    val type: String,
    val date: String,
    val dueDate: String? = null,
    val description: String,
    val amountCents: Long,
    val balanceAfterCents: Long,
)

/** Everything before the current owners' period, as one amount without details. */
@Serializable
data class CarriedForwardResponse(val amountCents: Long, val throughDate: String)

@Serializable
data class AccountTransactionsPageResponse(
    /** Newest first. */
    val transactions: List<AccountTransactionResponse>,
    val carriedForward: CarriedForwardResponse? = null,
    val page: Int,
    val pageSize: Int,
    val total: Int,
)
