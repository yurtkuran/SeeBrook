package cc.seebrook.app.http

import cc.seebrook.finance.ChangeRecurringPlan
import cc.seebrook.finance.CompleteMethodSetup
import cc.seebrook.finance.Frequency
import cc.seebrook.finance.GetRecurring
import cc.seebrook.finance.RecurringPlan
import cc.seebrook.finance.RemoveSavedMethod
import cc.seebrook.finance.SavedMethod
import cc.seebrook.finance.SetUpRecurringPlan
import cc.seebrook.finance.StartMethodSetup
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.shared.api.CompleteMethodSetupRequest
import cc.seebrook.shared.api.FrequencyOptionResponse
import cc.seebrook.shared.api.MethodSetupResponse
import cc.seebrook.shared.api.RecurringPlanResponse
import cc.seebrook.shared.api.RecurringQuoteChangedResponse
import cc.seebrook.shared.api.RecurringResponse
import cc.seebrook.shared.api.SavedMethodResponse
import cc.seebrook.shared.api.SetUpRecurringPlanRequest
import cc.seebrook.shared.api.StartMethodSetupRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Clock
import java.time.Duration
import java.util.UUID

class RecurringActions(db: Database, providers: PaymentProviders, clock: Clock = Clock.systemUTC()) {
    val get = GetRecurring(db, providers, clock)
    val startSetup = StartMethodSetup(db, providers)
    val completeSetup = CompleteMethodSetup(db, providers)
    val removeMethod = RemoveSavedMethod(db, providers)
    val setUpPlan = SetUpRecurringPlan(db, providers, clock)
    val changePlan = ChangeRecurringPlan(db, clock)

    companion object {
        /** Saving a method, setting up or changing a plan, and resuming it need a recent sign-in (GA-04). */
        val REAUTH_WINDOW: Duration = Duration.ofMinutes(10)
    }
}

/**
 * Recurring payments for one property (ED-59). Same answers as the payment routes: 404 `not_found`
 * for properties the user does not actively own (GA-06), 404 `finance_unavailable` without an
 * account, 503 `payments_unavailable` while the HOA's payments are paused. Changes that can lead to
 * charges answer 403 `reauthentication_required` unless the owner signed in within the last 10
 * minutes; pausing and canceling never do.
 */
fun Route.recurringRoutes(accounts: AccountActions, actions: RecurringActions) {
    authenticate(FIREBASE_AUTH) {
        route("/v1/properties/{propertyId}/recurring") {
            get {
                val actor = call.requireUser(accounts) ?: return@get
                val propertyId = call.propertyId() ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
                when (val result = io { actions.get(actor, propertyId) }) {
                    is GetRecurring.Result.Found -> call.respond(result.overview.toResponse())
                    GetRecurring.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    GetRecurring.Result.NoAccount -> call.error(HttpStatusCode.NotFound, "finance_unavailable")
                }
            }

            post("/methods/setup") {
                val actor = call.requireUser(accounts) ?: return@post
                if (!call.recentlySignedIn()) return@post call.error(HttpStatusCode.Forbidden, "reauthentication_required")
                val propertyId = call.propertyId() ?: return@post call.error(HttpStatusCode.NotFound, "not_found")
                val method = runCatching { call.receive<StartMethodSetupRequest>() }.getOrNull()?.method?.let(PaymentMethod::fromCode)
                    ?: return@post call.error(HttpStatusCode.BadRequest, "invalid_method")
                when (val result = io { actions.startSetup(actor, propertyId, method) }) {
                    is StartMethodSetup.Result.Started ->
                        call.respond(HttpStatusCode.Created, MethodSetupResponse(result.setupId, result.provider, result.clientParams))
                    StartMethodSetup.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    StartMethodSetup.Result.NoAccount -> call.error(HttpStatusCode.NotFound, "finance_unavailable")
                    StartMethodSetup.Result.Unavailable -> call.error(HttpStatusCode.ServiceUnavailable, "payments_unavailable")
                    StartMethodSetup.Result.NotOffered -> call.error(HttpStatusCode.Conflict, "recurring_not_offered")
                    StartMethodSetup.Result.MethodNotAccepted -> call.error(HttpStatusCode.BadRequest, "method_not_accepted")
                    StartMethodSetup.Result.ProviderUnavailable -> call.error(HttpStatusCode.ServiceUnavailable, "provider_unavailable")
                    StartMethodSetup.Result.ProviderRejected -> call.error(HttpStatusCode.BadGateway, "provider_rejected")
                }
            }

            post("/methods/complete") {
                val actor = call.requireUser(accounts) ?: return@post
                val propertyId = call.propertyId() ?: return@post call.error(HttpStatusCode.NotFound, "not_found")
                val setupId = runCatching { call.receive<CompleteMethodSetupRequest>() }.getOrNull()?.setupId
                    ?.takeIf { it.length in 1..255 } ?: return@post call.error(HttpStatusCode.BadRequest, "invalid_request")
                when (val result = io { actions.completeSetup(actor, propertyId, setupId) }) {
                    is CompleteMethodSetup.Result.Saved -> call.respond(HttpStatusCode.Created, result.method.toResponse())
                    CompleteMethodSetup.Result.Pending -> call.error(HttpStatusCode.Conflict, "setup_pending")
                    CompleteMethodSetup.Result.NotCompleted -> call.error(HttpStatusCode.Conflict, "setup_not_completed")
                    CompleteMethodSetup.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    CompleteMethodSetup.Result.ProviderUnavailable -> call.error(HttpStatusCode.ServiceUnavailable, "provider_unavailable")
                }
            }

            delete("/methods/{methodId}") {
                val actor = call.requireUser(accounts) ?: return@delete
                val propertyId = call.propertyId() ?: return@delete call.error(HttpStatusCode.NotFound, "not_found")
                val methodId = call.parameters["methodId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.error(HttpStatusCode.NotFound, "not_found")
                when (io { actions.removeMethod(actor, propertyId, methodId) }) {
                    RemoveSavedMethod.Result.Removed -> call.respond(HttpStatusCode.NoContent)
                    RemoveSavedMethod.Result.InUse -> call.error(HttpStatusCode.Conflict, "method_in_use")
                    RemoveSavedMethod.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    RemoveSavedMethod.Result.ProviderUnavailable -> call.error(HttpStatusCode.ServiceUnavailable, "provider_unavailable")
                }
            }

            put("/plan") {
                val actor = call.requireUser(accounts) ?: return@put
                if (!call.recentlySignedIn()) return@put call.error(HttpStatusCode.Forbidden, "reauthentication_required")
                val propertyId = call.propertyId() ?: return@put call.error(HttpStatusCode.NotFound, "not_found")
                val body = runCatching { call.receive<SetUpRecurringPlanRequest>() }.getOrNull()
                    ?: return@put call.error(HttpStatusCode.BadRequest, "invalid_request")
                val methodId = runCatching { UUID.fromString(body.savedMethodId) }.getOrNull()
                    ?: return@put call.error(HttpStatusCode.BadRequest, "invalid_request")
                val frequency = Frequency.fromCode(body.frequency) ?: return@put call.error(HttpStatusCode.BadRequest, "invalid_frequency")
                val request = SetUpRecurringPlan.Request(methodId, frequency, body.duesCents, body.feeCents, body.totalCents)
                when (val result = io { actions.setUpPlan(actor, propertyId, request) }) {
                    is SetUpRecurringPlan.Result.Saved ->
                        call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.plan.toResponse())
                    is SetUpRecurringPlan.Result.QuoteChanged ->
                        call.respond(HttpStatusCode.Conflict, RecurringQuoteChangedResponse("quote_changed", result.feeCents, result.totalCents))
                    is SetUpRecurringPlan.Result.Invalid -> call.error(HttpStatusCode.BadRequest, "invalid_amount")
                    SetUpRecurringPlan.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    SetUpRecurringPlan.Result.NoAccount -> call.error(HttpStatusCode.NotFound, "finance_unavailable")
                    SetUpRecurringPlan.Result.Unavailable -> call.error(HttpStatusCode.ServiceUnavailable, "payments_unavailable")
                    SetUpRecurringPlan.Result.NotOffered -> call.error(HttpStatusCode.Conflict, "frequency_not_offered")
                    SetUpRecurringPlan.Result.MethodNotFound -> call.error(HttpStatusCode.NotFound, "method_not_found")
                    SetUpRecurringPlan.Result.MethodNotAccepted -> call.error(HttpStatusCode.BadRequest, "method_not_accepted")
                }
            }

            post("/plan/{action}") {
                val actor = call.requireUser(accounts) ?: return@post
                val propertyId = call.propertyId() ?: return@post call.error(HttpStatusCode.NotFound, "not_found")
                val action = when (call.parameters["action"]) {
                    "pause" -> ChangeRecurringPlan.Action.PAUSE
                    "resume" -> ChangeRecurringPlan.Action.RESUME
                    "cancel" -> ChangeRecurringPlan.Action.CANCEL
                    else -> return@post call.error(HttpStatusCode.NotFound, "not_found")
                }
                if (action == ChangeRecurringPlan.Action.RESUME && !call.recentlySignedIn()) {
                    return@post call.error(HttpStatusCode.Forbidden, "reauthentication_required")
                }
                when (val result = io { actions.changePlan(actor, propertyId, action) }) {
                    is ChangeRecurringPlan.Result.Changed -> call.respond(result.plan.toResponse())
                    ChangeRecurringPlan.Result.NoPlan -> call.error(HttpStatusCode.NotFound, "no_plan")
                    ChangeRecurringPlan.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    ChangeRecurringPlan.Result.NeedsApproval -> call.error(HttpStatusCode.Conflict, "plan_needs_approval")
                    ChangeRecurringPlan.Result.Unchanged -> call.error(HttpStatusCode.Conflict, "plan_unchanged")
                }
            }
        }
    }
}

private fun RoutingCall.propertyId(): UUID? = parameters["propertyId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/** Compared with real time, like the token's own expiry, not with the business clock. */
private fun RoutingCall.recentlySignedIn(): Boolean =
    !identity().authTime.isBefore(java.time.Instant.now().minus(RecurringActions.REAUTH_WINDOW))

private fun SavedMethod.toResponse() = SavedMethodResponse(id.toString(), method.code, label)

private fun RecurringPlan.toResponse() = RecurringPlanResponse(
    id = id.toString(),
    frequency = frequency.code,
    methodId = method.id.toString(),
    method = method.method.code,
    methodLabel = method.label,
    duesCents = duesCents,
    feeCents = feeCents,
    totalCents = totalCents,
    status = status.code,
    statusReason = statusReason,
    nextDueDate = nextDueDate?.toString(),
    nextChargeDate = nextChargeDate?.toString(),
)

private fun GetRecurring.Overview.toResponse() = RecurringResponse(
    options = options.map { FrequencyOptionResponse(it.frequency.code, it.firstDueDate.toString(), it.firstChargeDate.toString()) },
    methods = methods.map { it.toResponse() },
    plan = plan?.toResponse(),
    available = available,
    acceptedMethods = acceptedMethods.map { it.code }.sorted(),
)
