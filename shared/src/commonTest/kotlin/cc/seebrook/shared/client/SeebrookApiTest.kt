package cc.seebrook.shared.client

import cc.seebrook.shared.api.HealthStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SeebrookApiTest {
    private val requests = mutableListOf<Pair<String, String?>>()

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String) =
        SeebrookApi(
            "http://seebrook.test/",
            MockEngine { request ->
                requests += request.url.toString() to request.headers[HttpHeaders.Authorization]
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

    @Test
    fun sendsTheTokenAndIgnoresFieldsItDoesNotKnow() = runTest {
        val api = api(body = """{"id":"u1","email":"a@example.test","displayName":null,"addedLater":true}""")
        api.idToken = "tok"
        val me = api.me()
        assertEquals("a@example.test", me.email)
        assertEquals(listOf<Pair<String, String?>>("http://seebrook.test/v1/me" to "Bearer tok"), requests)
    }

    @Test
    fun healthNeedsNoSignInAndReportsADownServer() = runTest {
        val health = api(HttpStatusCode.ServiceUnavailable, """{"status":"down","checks":{"database":"down"}}""").health()
        assertEquals(HealthStatus.DOWN, health.status)
        assertNull(requests.single().second)
    }

    @Test
    fun errorsCarryTheApiCode() = runTest {
        val api = api(HttpStatusCode.Forbidden, """{"error":"not_registered"}""").apply { idToken = "tok" }
        val e = assertFailsWith<ApiException> { api.myProperties() }
        assertEquals(ApiException.Kind.FORBIDDEN, e.kind)
        assertEquals("not_registered", e.code)
        assertEquals(403, e.status)
    }

    @Test
    fun signedOutCallsAreNotSent() = runTest {
        val e = assertFailsWith<ApiException> { api(body = "{}").me() }
        assertEquals(ApiException.Kind.UNAUTHORIZED, e.kind)
        assertEquals(0, requests.size)
    }

    @Test
    fun unreachableServerAndUnreadableResponses() = runTest {
        val down = SeebrookApi("http://seebrook.test", MockEngine { throw IOException("connection refused") }).apply { idToken = "t" }
        assertEquals(ApiException.Kind.NETWORK, assertFailsWith<ApiException> { down.me() }.kind)

        val garbled = api(body = "<html>").apply { idToken = "t" }
        assertEquals("bad_response", assertFailsWith<ApiException> { garbled.me() }.code)

        val proxy = SeebrookApi("http://seebrook.test", MockEngine { respondError(HttpStatusCode.BadGateway, "<html>") }).apply { idToken = "t" }
        val e = assertFailsWith<ApiException> { proxy.me() }
        assertEquals(ApiException.Kind.SERVER, e.kind)
        assertNull(e.code)
    }
}
