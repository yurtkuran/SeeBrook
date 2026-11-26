package cc.seebrook.app.http

import cc.seebrook.finance.HandlePaymentEvent
import cc.seebrook.finance.InitiatePayment
import cc.seebrook.finance.ListPayments
import cc.seebrook.finance.Payment
import cc.seebrook.finance.PaymentQuote
import cc.seebrook.finance.QuotePayment
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.shared.api.PaymentOptionResponse
import cc.seebrook.shared.api.PaymentQuoteRequest
import cc.seebrook.shared.api.PaymentQuoteResponse
import cc.seebrook.shared.api.PaymentResponse
import cc.seebrook.shared.api.PaymentsResponse
import cc.seebrook.shared.api.QuoteChangedResponse
import cc.seebrook.shared.api.StartPaymentRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter
import java.util.UUID

class PaymentActions(db: Database, providers: PaymentProviders) {
    val quote = QuotePayment(db, providers)
    val start = InitiatePayment(db, providers)
    val list = ListPayments(db)
    val handleEvent = HandlePaymentEvent(db, providers)
}

@Serializable
private data class WebhookAck(val received: Boolean)

/** Webhook bodies are small JSON documents; anything larger is refused unread. */
private const val MAX_WEBHOOK_BYTES = 256 * 1024

/**
 * Owner payments for one property (ED-43) and the providers' webhooks. Owner routes answer like
 * the finance routes: 404 `not_found` for properties the user does not actively own (GA-06), 404
 * `finance_unavailable` without an account, and 503 `payments_unavailable` when the HOA does not
 * take payments right now (not set up, suspended or provider down), so the app disables paying.
 */
fun Route.paymentRoutes(accounts: AccountActions, actions: PaymentActions) {
    authenticate(FIREBASE_AUTH) {
        route("/v1/properties/{propertyId}/payments") {
            post("/quote") {
                val actor = call.requireUser(accounts) ?: return@post
                val propertyId = call.propertyId() ?: return@post call.error(HttpStatusCode.NotFound, "not_found")
                val body = runCatching { call.receive<PaymentQuoteRequest>() }.getOrNull()
                    ?: return@post call.error(HttpStatusCode.BadRequest, "invalid_request")
                when (val result = io { actions.quote(actor, propertyId, body.amountCents) }) {
                    is QuotePayment.Result.Quoted -> call.respond(result.quote.toResponse(propertyId))
                    QuotePayment.Result.InvalidAmount -> call.error(HttpStatusCode.BadRequest, "invalid_amount")
                    QuotePayment.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    QuotePayment.Result.NoAccount -> call.error(HttpStatusCode.NotFound, "finance_unavailable")
                    QuotePayment.Result.Unavailable -> call.error(HttpStatusCode.ServiceUnavailable, "payments_unavailable")
                }
            }

            post {
                val actor = call.requireUser(accounts) ?: return@post
                val propertyId = call.propertyId() ?: return@post call.error(HttpStatusCode.NotFound, "not_found")
                val body = runCatching { call.receive<StartPaymentRequest>() }.getOrNull()
                    ?: return@post call.error(HttpStatusCode.BadRequest, "invalid_request")
                val method = PaymentMethod.fromCode(body.method) ?: return@post call.error(HttpStatusCode.BadRequest, "invalid_method")
                val request = InitiatePayment.Request(method, body.amountCents, body.feeCents, body.totalCents, body.idempotencyKey)
                when (val result = io { actions.start(actor, propertyId, request) }) {
                    is InitiatePayment.Result.Started -> call.respond(
                        HttpStatusCode.Created,
                        result.payment.toResponse(result.provider, result.clientParams.takeIf { it.isNotEmpty() }),
                    )
                    is InitiatePayment.Result.QuoteChanged ->
                        call.respond(HttpStatusCode.Conflict, QuoteChangedResponse("quote_changed", result.quote.toResponse(propertyId)))
                    InitiatePayment.Result.IdempotencyConflict -> call.error(HttpStatusCode.Conflict, "idempotency_conflict")
                    InitiatePayment.Result.MethodNotAccepted -> call.error(HttpStatusCode.BadRequest, "method_not_accepted")
                    is InitiatePayment.Result.Invalid -> call.error(HttpStatusCode.BadRequest, "invalid_payment")
                    InitiatePayment.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    InitiatePayment.Result.NoAccount -> call.error(HttpStatusCode.NotFound, "finance_unavailable")
                    InitiatePayment.Result.Unavailable -> call.error(HttpStatusCode.ServiceUnavailable, "payments_unavailable")
                    InitiatePayment.Result.ProviderUnavailable -> call.error(HttpStatusCode.ServiceUnavailable, "provider_unavailable")
                    InitiatePayment.Result.ProviderRejected -> call.error(HttpStatusCode.BadGateway, "provider_rejected")
                }
            }

            get {
                val actor = call.requireUser(accounts) ?: return@get
                val propertyId = call.propertyId() ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
                when (val result = io { actions.list(actor, propertyId) }) {
                    is ListPayments.Result.Listed -> call.respond(PaymentsResponse(result.payments.map { it.toResponse(null, null) }))
                    ListPayments.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    ListPayments.Result.NoAccount -> call.error(HttpStatusCode.NotFound, "finance_unavailable")
                }
            }
        }
    }

    // Called by the provider, not by users: authenticated by the provider's signature (GA-17).
    post("/v1/webhooks/payments/{provider}") {
        val provider = call.parameters["provider"].orEmpty()
        val length = call.request.headers["Content-Length"]?.toLongOrNull()
        if (length != null && length > MAX_WEBHOOK_BYTES) return@post call.error(HttpStatusCode.PayloadTooLarge, "too_large")
        val payload = call.receive<ByteArray>()
        if (payload.size > MAX_WEBHOOK_BYTES) return@post call.error(HttpStatusCode.PayloadTooLarge, "too_large")
        val headers = call.request.headers
        when (io { actions.handleEvent(provider, payload) { headers[it] } }) {
            is HandlePaymentEvent.Result.Accepted -> call.respond(WebhookAck(true))
            is HandlePaymentEvent.Result.Rejected -> call.error(HttpStatusCode.BadRequest, "invalid_webhook")
            HandlePaymentEvent.Result.UnknownProvider -> call.error(HttpStatusCode.NotFound, "not_found")
        }
    }
}

private fun RoutingCall.propertyId(): UUID? = parameters["propertyId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun PaymentQuote.toResponse(propertyId: UUID) = PaymentQuoteResponse(
    propertyId = propertyId.toString(),
    currency = "USD",
    amountCents = duesCents,
    amountDueCents = amountDueCents,
    options = options.map { PaymentOptionResponse(it.method.code, it.feeCents, it.totalCents) },
)

private fun Payment.toResponse(provider: String?, clientParams: Map<String, String>?) = PaymentResponse(
    id = id.toString(),
    reference = reference,
    status = status.code,
    method = method.code,
    amountCents = duesCents,
    feeCents = feeCents,
    totalCents = totalCents,
    currency = currency,
    createdAt = DateTimeFormatter.ISO_INSTANT.format(createdAt),
    succeededAt = succeededAt?.let(DateTimeFormatter.ISO_INSTANT::format),
    failureReason = failureReason,
    failureMessage = failureMessage,
    provider = provider,
    clientParams = clientParams,
)
