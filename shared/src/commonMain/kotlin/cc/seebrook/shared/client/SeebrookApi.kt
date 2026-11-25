package cc.seebrook.shared.client

import cc.seebrook.shared.api.AcceptInvitationRequest
import cc.seebrook.shared.api.AcceptInvitationResponse
import cc.seebrook.shared.api.AccountSummaryResponse
import cc.seebrook.shared.api.AccountTransactionsPageResponse
import cc.seebrook.shared.api.DocumentCategoriesResponse
import cc.seebrook.shared.api.DocumentsPageResponse
import cc.seebrook.shared.api.DownloadLinkResponse
import cc.seebrook.shared.api.ErrorResponse
import cc.seebrook.shared.api.HealthResponse
import cc.seebrook.shared.api.MeResponse
import cc.seebrook.shared.api.PaymentQuoteRequest
import cc.seebrook.shared.api.PaymentQuoteResponse
import cc.seebrook.shared.api.PaymentResponse
import cc.seebrook.shared.api.PaymentsResponse
import cc.seebrook.shared.api.StartPaymentRequest
import cc.seebrook.shared.api.PropertiesResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Seebrook's HTTP API for the mobile apps (ED-15, ED-48). It only sends requests and decodes
 * responses: every rule and figure comes from the backend (ED-17).
 *
 * From Swift each call is `async throws` and throws [ApiException]; call it on the main actor.
 */
class SeebrookApi internal constructor(baseUrl: String, engine: HttpClientEngine) {
    /** The platform's HTTP stack (URLSession on iOS). */
    constructor(baseUrl: String) : this(baseUrl, platformEngine())

    private val base = baseUrl.trimEnd('/')

    /** The signed-in user's ID token, sent with every request that needs one; null when signed out. */
    var idToken: String? = null

    private val http = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(JSON) }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
    }

    /** Whether the server and its database are up; needs no sign-in. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun health(): HealthResponse = call(signedIn = false) { http.get("$base/health/ready", it) }

    @Throws(ApiException::class, CancellationException::class)
    suspend fun me(): MeResponse = call { http.get("$base/v1/me", it) }

    /**
     * Accepts an invitation for the signed-in user, which registers them on their first one. Use
     * [InvitationCode.fromInput] on what the owner entered. Fails with `invitation_not_found`,
     * `invitation_expired`, `invitation_no_longer_valid`, `email_not_verified`,
     * `invitation_email_mismatch` or `account_disabled`.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun acceptInvitation(code: String): AcceptInvitationResponse = call {
        http.post("$base/v1/invitations/accept") {
            it()
            contentType(ContentType.Application.Json)
            setBody(AcceptInvitationRequest(code))
        }
    }

    /** The properties the user currently owns. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun myProperties(): PropertiesResponse = call { http.get("$base/v1/me/properties", it) }

    /**
     * The property's balance, as computed by the server (ED-42). Fails with `not_found`, or
     * `finance_unavailable` when the HOA has not set up the unit's account yet.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun financeSummary(propertyId: String): AccountSummaryResponse = call {
        http.get("${property(propertyId)}/finance/summary", it)
    }

    /** One page (from 1) of the property's transactions, newest first, with the carried-forward amount (ED-42). */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun accountTransactions(propertyId: String, page: Int, pageSize: Int): AccountTransactionsPageResponse = call {
        http.get("${property(propertyId)}/finance/transactions") {
            it()
            url.parameters.append("page", page.toString())
            url.parameters.append("pageSize", pageSize.toString())
        }
    }

    /**
     * The fee and total of paying [amountCents] by each method the HOA accepts (ED-43). Fails with
     * `invalid_amount`, `payments_unavailable` (paused or not offered) or `finance_unavailable`.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun paymentQuote(propertyId: String, amountCents: Long): PaymentQuoteResponse = call {
        http.post("${property(propertyId)}/payments/quote") {
            it()
            contentType(ContentType.Application.Json)
            setBody(PaymentQuoteRequest(amountCents))
        }
    }

    /**
     * Starts a payment for exactly the option the owner approved. Send the same [StartPaymentRequest.idempotencyKey]
     * when retrying the same approval. Fails with `quote_changed` (nothing charged: quote again and
     * ask the owner to confirm), `idempotency_conflict`, `method_not_accepted`, `payments_unavailable`,
     * `provider_unavailable` or `provider_rejected`.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun startPayment(propertyId: String, request: StartPaymentRequest): PaymentResponse = call {
        http.post("${property(propertyId)}/payments") {
            it()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    /** The property's online payments, newest first, with their status. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun payments(propertyId: String): PaymentsResponse = call { http.get("${property(propertyId)}/payments", it) }

    private fun property(propertyId: String) = "$base/v1/properties/${propertyId.encodeURLPathPart()}"

    /** Categories holding at least one document the owner can see in the HOA, with counts (ED-39). */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun documentCategories(hoaId: String): DocumentCategoriesResponse = call {
        http.get("${hoa(hoaId)}/document-categories", it)
    }

    /**
     * One page (from 1) of the HOA's documents, newest upload first. [search] matches title,
     * description, category and tags; [categoryId] filters by category; null for neither.
     * Fails with `not_found` when the owner has no property in the HOA (ED-39).
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun documents(hoaId: String, search: String?, categoryId: String?, page: Int, pageSize: Int): DocumentsPageResponse =
        call {
            http.get("${hoa(hoaId)}/documents") {
                it()
                url.parameters.apply {
                    search?.trim()?.takeIf(String::isNotEmpty)?.let { q -> append("q", q) }
                    categoryId?.let { c -> append("category", c) }
                    append("page", page.toString())
                    append("pageSize", pageSize.toString())
                }
            }
        }

    /**
     * A new short-lived link to the document's PDF. Each call issues a link and is audited, so ask
     * for one each time the owner opens a document and never store it. Fails with `not_found`, or
     * `documents_unavailable` when storage is down (ED-39).
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun documentDownloadLink(hoaId: String, documentId: String): DownloadLinkResponse = call {
        http.post("${hoa(hoaId)}/documents/${documentId.encodeURLPathPart()}/download-link", it)
    }

    private fun hoa(hoaId: String) = "$base/v1/hoas/${hoaId.encodeURLPathPart()}"

    private suspend inline fun <reified T> call(
        signedIn: Boolean = true,
        crossinline send: suspend (HttpRequestBuilder.() -> Unit) -> HttpResponse,
    ): T {
        val token = idToken
        if (signedIn && token == null) throw ApiException(ApiException.Kind.UNAUTHORIZED, "signed_out", 0)
        val response = try {
            send { if (signedIn && token != null) bearerAuth(token) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(ApiException.Kind.NETWORK, null, 0, e.message)
        }
        val status = response.status.value
        if (!response.status.isSuccess() && !(status == 503 && T::class == HealthResponse::class)) {
            val code = try {
                response.body<ErrorResponse>().error
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            throw ApiException(ApiException.Kind.of(status), code, status)
        }
        return try {
            response.body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(ApiException.Kind.SERVER, "bad_response", status, e.message)
        }
    }

    internal companion object {
        /** Unknown fields are ignored: installed app versions must keep working as the API grows. */
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** Why a call failed. [code] is the API's stable error code (`{"error": "<code>"}`), when it sent one. */
class ApiException(val kind: Kind, val code: String?, val status: Int, detail: String? = null) :
    Exception("${kind.name.lowercase()}${code?.let { " ($it)" } ?: ""}${if (status > 0) " HTTP $status" else ""}${detail?.let { ": $it" } ?: ""}") {
    enum class Kind {
        /** No response: offline, server unreachable or timed out. */
        NETWORK,

        /** Not signed in, or the sign-in expired: sign in again. */
        UNAUTHORIZED,

        /** Signed in but not allowed (for example `not_registered`, `account_disabled`). */
        FORBIDDEN,
        NOT_FOUND,

        /** The request was refused (validation, conflict, gone). */
        REJECTED,
        SERVER,
        ;

        internal companion object {
            fun of(status: Int) = when (status) {
                401 -> UNAUTHORIZED
                403 -> FORBIDDEN
                404 -> NOT_FOUND
                in 400..499 -> REJECTED
                else -> SERVER
            }
        }
    }
}

internal expect fun platformEngine(): HttpClientEngine
