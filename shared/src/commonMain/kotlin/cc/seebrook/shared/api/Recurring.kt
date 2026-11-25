package cc.seebrook.shared.api

import kotlinx.serialization.Serializable

/** One frequency the HOA offers, with the first due date and the business day it is charged (ED-59). */
@Serializable
data class FrequencyOptionResponse(val frequency: String, val firstDueDate: String, val firstChargeDate: String)

/** A payment method the provider keeps for the owner; [label] is e.g. "Visa •••• 4242". */
@Serializable
data class SavedMethodResponse(val id: String, val method: String, val label: String)

/**
 * The owner's recurring plan for the property. [status] is active, paused or needs_approval (the
 * fee changed or the method can no longer be used: [statusReason] says which).
 */
@Serializable
data class RecurringPlanResponse(
    val id: String,
    val frequency: String,
    val methodId: String,
    val method: String,
    val methodLabel: String,
    val duesCents: Long,
    val feeCents: Long,
    val totalCents: Long,
    val currency: String = "USD",
    val status: String,
    val statusReason: String? = null,
    val nextDueDate: String? = null,
    val nextChargeDate: String? = null,
)

/**
 * Recurring payments for a property: the frequencies offered (empty when the HOA offers none),
 * the owner's saved methods, their plan, and whether methods can be saved and plans set up now.
 */
@Serializable
data class RecurringResponse(
    val options: List<FrequencyOptionResponse>,
    val methods: List<SavedMethodResponse>,
    val plan: RecurringPlanResponse? = null,
    val available: Boolean,
    val acceptedMethods: List<String>,
)

@Serializable
data class StartMethodSetupRequest(val method: String)

/** What the provider's SDK needs to collect the method's details (X-02); never stored by the app. */
@Serializable
data class MethodSetupResponse(val setupId: String, val provider: String, val clientParams: Map<String, String>)

@Serializable
data class CompleteMethodSetupRequest(val setupId: String)

/** The plan the owner approves: fixed dues, its exact fee and total (GA-23). */
@Serializable
data class SetUpRecurringPlanRequest(
    val savedMethodId: String,
    val frequency: String,
    val duesCents: Long,
    val feeCents: Long,
    val totalCents: Long,
)

/** 409 when the fee differs from what the owner saw: nothing changed; approve the new figures. */
@Serializable
data class RecurringQuoteChangedResponse(val error: String, val feeCents: Long, val totalCents: Long)
