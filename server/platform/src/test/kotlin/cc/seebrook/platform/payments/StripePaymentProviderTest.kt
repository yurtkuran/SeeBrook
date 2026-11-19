package cc.seebrook.platform.payments

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The Stripe adapter (ED-43) against a local fake of Stripe's API, and its webhook verification. */
class StripePaymentProviderTest {
    private data class Call(val method: String, val path: String, val headers: Map<String, String?>, val form: List<Pair<String, String>>)

    private val calls = mutableListOf<Call>()
    private var reply: () -> Pair<Int, String> = {
        200 to """{"id":"pi_123","object":"payment_intent","status":"requires_payment_method","client_secret":"pi_123_secret_abc","last_payment_error":null}"""
    }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val form = body.split('&').filter { it.isNotEmpty() }.map {
                val (k, v) = it.split('=', limit = 2)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }
            val headers = listOf("Authorization", "Stripe-Account", "Stripe-Version", "Idempotency-Key").associateWith { exchange.requestHeaders.getFirst(it) }
            synchronized(calls) { calls += Call(exchange.requestMethod, exchange.requestURI.path, headers, form) }
            val (status, response) = reply()
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val now = Instant.parse("2026-11-19T15:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val stripe = StripePaymentProvider(
        secretKey = "sk_test_platform",
        publishableKey = "pk_test_platform",
        webhookSecret = "whsec_test",
        apiBase = "http://127.0.0.1:${server.address.port}",
        clock = clock,
    )

    @AfterEach
    fun stop() = server.stop(0)

    private fun request(method: PaymentMethod = PaymentMethod.CARD) = CreatePaymentRequest(
        merchantRef = "acct_hoa1", amountCents = 35_500, currency = "USD", method = method,
        idempotencyKey = "seebrook-payment-1", description = "HOA dues PAY-1",
        metadata = mapOf("seebrook_payment_id" to "p-1"),
    )

    @Test
    fun `a payment is a PaymentIntent on the HOA's connected account, limited to the chosen method`() {
        val payment = stripe.createPayment(request())

        payment.providerPaymentId shouldBe "pi_123"
        payment.status shouldBe ProviderPaymentStatus.AWAITING_OWNER
        payment.clientParams shouldBe mapOf("clientSecret" to "pi_123_secret_abc", "publishableKey" to "pk_test_platform", "stripeAccount" to "acct_hoa1")
        payment.toString() shouldNotContain "secret_abc"

        val call = calls.single()
        call.method shouldBe "POST"
        call.path shouldBe "/v1/payment_intents"
        call.headers shouldBe mapOf(
            "Authorization" to "Bearer sk_test_platform",
            "Stripe-Account" to "acct_hoa1",
            "Stripe-Version" to StripePaymentProvider.API_VERSION,
            "Idempotency-Key" to "seebrook-payment-1",
        )
        call.form shouldBe listOf(
            "amount" to "35500",
            "currency" to "usd",
            "allowed_payment_method_types[]" to "card",
            "description" to "HOA dues PAY-1",
            "metadata[seebrook_payment_id]" to "p-1",
        )
        stripe.createPayment(request(PaymentMethod.ACH))
        calls.last().form shouldBe calls.first().form.map { if (it.first == "allowed_payment_method_types[]") it.first to "us_bank_account" else it }
    }

    @Test
    fun `Stripe errors are retryable or final, and never echo Stripe's message`() {
        reply = { 400 to """{"error":{"code":"account_invalid","message":"No such account: acct_hoa1 owner@example.test"}}""" }
        shouldThrow<PaymentRequestRejectedException> { stripe.createPayment(request()) }.message.let {
            it shouldContain "account_invalid"
            it shouldNotContain "owner@example.test"
        }
        reply = { 500 to """{"error":{"type":"api_error"}}""" }
        shouldThrow<PaymentProviderException> { stripe.createPayment(request()) }.shouldBeInstanceOf<PaymentProviderException>()
        reply = { 429 to "{}" }
        shouldThrow<PaymentProviderException> { stripe.createPayment(request()) }::class shouldBe PaymentProviderException::class
        shouldThrow<IllegalArgumentException> { stripe.createPayment(request().copy(merchantRef = "acct_1/../x")) }
        stripe.toString() shouldNotContain "sk_test"
    }

    @Test
    fun `payment lookup maps Stripe's states and reports the amount`() {
        val states = mapOf(
            """"status":"processing"""" to ProviderPaymentStatus.PROCESSING,
            """"status":"succeeded"""" to ProviderPaymentStatus.SUCCEEDED,
            """"status":"canceled"""" to ProviderPaymentStatus.CANCELED,
            """"status":"requires_action"""" to ProviderPaymentStatus.AWAITING_OWNER,
            """"status":"requires_payment_method","last_payment_error":null""" to ProviderPaymentStatus.AWAITING_OWNER,
            """"status":"requires_payment_method","last_payment_error":{"code":"card_declined"}""" to ProviderPaymentStatus.FAILED,
        )
        states.forEach { (json, expected) ->
            reply = { 200 to """{"id":"pi_123","amount":15500,"currency":"usd",$json}""" }
            stripe.lookupPayment("acct_hoa1", "pi_123").status shouldBe expected
        }
        stripe.lookupPayment("acct_hoa1", "pi_123") shouldBe
            ProviderPaymentSnapshot("pi_123", ProviderPaymentStatus.FAILED, 15_500, "USD", "card_declined")
        calls.last().method shouldBe "GET"
        calls.last().path shouldBe "/v1/payment_intents/pi_123"
        calls.last().headers["Stripe-Account"] shouldBe "acct_hoa1"

        reply = { 200 to """{"id":"pi_123","status":"succeeded"}""" }
        shouldThrow<PaymentProviderException> { stripe.lookupPayment("acct_hoa1", "pi_123") } // no amount: never guessed
    }

    @Test
    fun `availability check reads the connected account's balance`() {
        reply = { 200 to """{"object":"balance"}""" }
        stripe.checkAvailable("acct_hoa1")
        calls.last().path shouldBe "/v1/balance"
        reply = { 503 to "{}" }
        shouldThrow<PaymentProviderException> { stripe.checkAvailable("acct_hoa1") }
    }

    private fun event(type: String, intent: String = """{"id":"pi_123","amount":35500,"currency":"usd","metadata":{"seebrook_payment_id":"p-1"}}""", live: Boolean = false) =
        """{"id":"evt_1","type":"$type","account":"acct_hoa1","livemode":$live,"data":{"object":$intent}}""".toByteArray()

    private fun verify(payload: ByteArray, signature: String? = WebhookSignature.header(payload, "whsec_test", clock)) =
        stripe.verifyWebhook(payload) { if (it == "Stripe-Signature") signature else null }

    @Test
    fun `a signed payment event is normalized`() {
        verify(event("payment_intent.succeeded")).shouldBeInstanceOf<WebhookVerification.Verified>().event shouldBe PaymentEvent.PaymentUpdated(
            eventId = "evt_1", type = "payment_intent.succeeded", merchantRef = "acct_hoa1", providerPaymentId = "pi_123",
            status = ProviderPaymentStatus.SUCCEEDED, amountCents = 35_500, currency = "USD", seebrookPaymentId = "p-1", failureCode = null,
        )
        val failed = event(
            "payment_intent.payment_failed",
            """{"id":"pi_123","amount":35500,"currency":"usd","last_payment_error":{"code":"card_declined","decline_code":"insufficient_funds","message":"Your card has insufficient funds."}}""",
        )
        (verify(failed).shouldBeInstanceOf<WebhookVerification.Verified>().event as PaymentEvent.PaymentUpdated).let {
            it.status shouldBe ProviderPaymentStatus.FAILED
            it.failureCode shouldBe "insufficient_funds"
        }
        verify(event("charge.refunded")).shouldBeInstanceOf<WebhookVerification.Verified>().event shouldBe PaymentEvent.Other("evt_1", "charge.refunded")
    }

    @Test
    fun `refund and dispute events are normalized with their PaymentIntent`() {
        fun adjusted(type: String, obj: String) =
            verify(event(type, obj)).shouldBeInstanceOf<WebhookVerification.Verified>().event.shouldBeInstanceOf<PaymentEvent.Adjusted>()

        adjusted("refund.created", """{"id":"re_1","object":"refund","amount":5000,"currency":"usd","payment_intent":"pi_123","status":"pending"}""") shouldBe
            PaymentEvent.Adjusted("evt_1", "refund.created", "acct_hoa1", "pi_123", AdjustmentKind.REFUND, "re_1", 5_000, "USD")
        adjusted("refund.failed", """{"id":"re_1","amount":5000,"currency":"usd","payment_intent":"pi_123"}""").kind shouldBe AdjustmentKind.REFUND_FAILED
        adjusted("charge.dispute.funds_withdrawn", """{"id":"dp_1","object":"dispute","amount":35500,"currency":"usd","payment_intent":"pi_123","charge":"ch_1"}""")
            .let {
                it.kind shouldBe AdjustmentKind.DISPUTE_WITHDRAWN
                it.providerRef shouldBe "dp_1"
                it.amountCents shouldBe 35_500
            }
        adjusted("charge.dispute.funds_reinstated", """{"id":"dp_1","amount":35500,"currency":"usd","payment_intent":"pi_123"}""").kind shouldBe
            AdjustmentKind.DISPUTE_REINSTATED
        verify(event("refund.created", """{"id":"re_2","amount":5000,"currency":"usd","payment_intent":null}""")).shouldBeInstanceOf<WebhookVerification.Rejected>()
        StripePaymentProvider.WEBHOOK_EVENTS.size shouldBe 8
    }

    @Test
    fun `forged, tampered, stale, unsigned or wrong-mode events are rejected`() {
        val payload = event("payment_intent.succeeded")
        fun rejected(result: WebhookVerification) = result.shouldBeInstanceOf<WebhookVerification.Rejected>().reason

        rejected(verify(payload, signature = null)) shouldContain "missing"
        rejected(verify(payload, WebhookSignature.header(payload, "whsec_other", clock))) shouldContain "does not match"
        rejected(verify(payload.copyOf().also { it[it.size - 3] = 'X'.code.toByte() }, WebhookSignature.header(payload, "whsec_test", clock))) shouldContain "does not match"
        val old = Clock.fixed(now.minusSeconds(301), ZoneOffset.UTC)
        rejected(verify(payload, WebhookSignature.header(payload, "whsec_test", old))) shouldContain "tolerance"
        rejected(verify(payload, "v1=abc")) shouldContain "timestamp"
        rejected(verify(event("payment_intent.succeeded", live = true))) shouldContain "livemode"

        // During a secret rotation Stripe sends one signature per secret; any valid one is enough.
        val good = WebhookSignature.sign(payload, now.epochSecond, "whsec_test")
        verify(payload, "t=${now.epochSecond},v1=deadbeef,v1=$good").shouldBeInstanceOf<WebhookVerification.Verified>()
    }
}
