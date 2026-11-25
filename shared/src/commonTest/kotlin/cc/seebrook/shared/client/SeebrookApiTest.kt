package cc.seebrook.shared.client

import cc.seebrook.shared.api.HealthStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SeebrookApiTest {
    private val requests = mutableListOf<Pair<String, String?>>()
    private val bodies = mutableListOf<String>()

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String) =
        SeebrookApi(
            "http://seebrook.test/",
            MockEngine { request ->
                requests += request.url.toString() to request.headers[HttpHeaders.Authorization]
                (request.body as? TextContent)?.let { bodies += it.text }
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
    fun documentsSendTheQueryAndDecodeThePage() = runTest {
        val api = api(
            body = """{"documents":[{"id":"d1","title":"Bylaws","description":"","categoryId":"c1",
                "categoryName":"Governing","tags":["rules"],"documentDate":null,
                "uploadedAt":"2026-11-15T12:00:00Z","pageCount":12,"sizeBytes":2400000}],
                "page":2,"pageSize":20,"total":21}""",
        ).apply { idToken = "tok" }
        val page = api.documents("h1", search = "  pool rules ", categoryId = "c1", page = 2, pageSize = 20)
        assertEquals("Bylaws", page.documents.single().title)
        assertEquals(21, page.total)
        assertEquals(
            "http://seebrook.test/v1/hoas/h1/documents?q=pool+rules&category=c1&page=2&pageSize=20",
            requests.single().first,
        )
    }

    @Test
    fun documentsLeaveOutABlankSearch() = runTest {
        api(body = """{"documents":[],"page":1,"pageSize":20,"total":0}""").apply { idToken = "tok" }
            .documents("h1", search = " ", categoryId = null, page = 1, pageSize = 20)
        assertEquals("http://seebrook.test/v1/hoas/h1/documents?page=1&pageSize=20", requests.single().first)
    }

    @Test
    fun downloadLinksArePosted() = runTest {
        val link = api(body = """{"url":"https://files.test/x.pdf?sig=1","expiresAt":"2026-11-24T12:05:00Z","fileName":"Bylaws.pdf"}""")
            .apply { idToken = "tok" }
            .documentDownloadLink("h1", "d1")
        assertEquals("Bylaws.pdf", link.fileName)
        assertEquals("http://seebrook.test/v1/hoas/h1/documents/d1/download-link", requests.single().first)
    }

    @Test
    fun hoaLogoLinksAreFetchedForTheProperty() = runTest {
        val logo = api(body = """{"url":"https://files.test/logo.png?sig=1","expiresAt":"2026-11-25T12:05:00Z","version":"v2"}""")
            .apply { idToken = "tok" }
            .hoaLogo("p1")
        assertEquals("v2", logo.version)
        assertEquals("http://seebrook.test/v1/properties/p1/hoa-logo", requests.single().first)
    }

    @Test
    fun financeSummaryDecodesCents() = runTest {
        val summary = api(
            body = """{"propertyId":"p1","currency":"USD","balanceCents":95000,"amountDueCents":95000,
                "overdueCents":50000,"notYetDueCents":45000,"creditCents":0,"nextDueDate":"2027-01-01",
                "asOfDate":"2026-11-24","updatedAt":"2026-11-24T15:00:00Z","pendingPaymentsCents":0,
                "onlinePayments":"available"}""",
        ).apply { idToken = "tok" }.financeSummary("p1")
        assertEquals(95000L, summary.balanceCents)
        assertEquals("2027-01-01", summary.nextDueDate)
        assertEquals("http://seebrook.test/v1/properties/p1/finance/summary", requests.single().first)
    }

    @Test
    fun transactionsSendThePageAndDecodeCarriedForward() = runTest {
        val page = api(
            body = """{"transactions":[{"id":"t1","reference":"2026-00001","type":"payment","date":"2026-11-02",
                "description":"Check payment","amountCents":-45000,"balanceAfterCents":0}],
                "carriedForward":{"amountCents":12000,"throughDate":"2025-12-31"},"page":1,"pageSize":20,"total":1}""",
        ).apply { idToken = "tok" }.accountTransactions("p1", page = 1, pageSize = 20)
        assertEquals(-45000L, page.transactions.single().amountCents)
        assertEquals(12000L, page.carriedForward?.amountCents)
        assertEquals("http://seebrook.test/v1/properties/p1/finance/transactions?page=1&pageSize=20", requests.single().first)
    }

    @Test
    fun paymentsSendTheApprovedOptionAndKey() = runTest {
        val api = api(
            HttpStatusCode.Created,
            """{"id":"p1","reference":"PAY-1","status":"created","method":"card","amountCents":95000,"feeCents":2785,
                "totalCents":97785,"currency":"USD","createdAt":"2026-11-24T12:00:00Z","provider":"simulated",
                "clientParams":{"simulated":"true"}}""",
        ).apply { idToken = "tok" }
        val payment = api.startPayment(
            "prop", cc.seebrook.shared.api.StartPaymentRequest("card", 95000, 2785, 97785, "key-1"),
        )
        assertEquals("PAY-1", payment.reference)
        assertEquals(mapOf("simulated" to "true"), payment.clientParams)
        assertEquals("http://seebrook.test/v1/properties/prop/payments", requests.single().first)
        assertEquals(
            """{"method":"card","amountCents":95000,"feeCents":2785,"totalCents":97785,"idempotencyKey":"key-1"}""",
            bodies.single(),
        )
    }

    @Test
    fun aChangedQuoteIsAnErrorWithItsCode() = runTest {
        val api = api(HttpStatusCode.Conflict, """{"error":"quote_changed","quote":{}}""").apply { idToken = "tok" }
        val e = assertFailsWith<ApiException> {
            api.startPayment("prop", cc.seebrook.shared.api.StartPaymentRequest("card", 95000, 2785, 97785, "key-1"))
        }
        assertEquals("quote_changed", e.code)
    }

    @Test
    fun quotesArePostedWithTheAmount() = runTest {
        val quote = api(
            body = """{"propertyId":"prop","currency":"USD","amountCents":95000,"amountDueCents":95000,
                "options":[{"method":"ach","feeCents":0,"totalCents":95000}]}""",
        ).apply { idToken = "tok" }.paymentQuote("prop", 95000)
        assertEquals("ach", quote.options.single().method)
        assertEquals("""{"amountCents":95000}""", bodies.single())
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

    @Test
    fun acceptsAnInvitation() = runTest {
        val api = api(body = """{"propertyId":"p1"}""").apply { idToken = "tok" }
        assertEquals("p1", api.acceptInvitation("code").propertyId)
        assertEquals(listOf<Pair<String, String?>>("http://seebrook.test/v1/invitations/accept" to "Bearer tok"), requests)
        assertEquals(listOf("""{"token":"code"}"""), bodies)

        val expired = api(HttpStatusCode.Gone, """{"error":"invitation_expired"}""").apply { idToken = "tok" }
        val e = assertFailsWith<ApiException> { expired.acceptInvitation("code") }
        assertEquals(ApiException.Kind.REJECTED to "invitation_expired", e.kind to e.code)
    }
}
