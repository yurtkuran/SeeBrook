package cc.seebrook.shared.client

import cc.seebrook.shared.api.ErrorResponse
import cc.seebrook.shared.api.HealthResponse
import cc.seebrook.shared.api.MeResponse
import cc.seebrook.shared.api.PropertiesResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
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

    /** The properties the user currently owns. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun myProperties(): PropertiesResponse = call { http.get("$base/v1/me/properties", it) }

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
