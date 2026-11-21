package cc.seebrook.app

import cc.seebrook.platform.payments.CreatePaymentRequest
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.StripePaymentProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/**
 * The Stripe adapter's requests against stripe-mock, Stripe's own mock of its API, which checks
 * every request against Stripe's published API definition (ED-43). No Stripe account is needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StripeMockTest {
    private val mock = GenericContainer("stripe/stripe-mock:v0.206.0")
        .withExposedPorts(12111)
        .waitingFor(Wait.forListeningPort())
        .also { it.start() }

    private val stripe = StripePaymentProvider(
        secretKey = "sk_test_123",
        publishableKey = "pk_test_123",
        webhookSecret = "whsec_test",
        apiBase = "http://${mock.host}:${mock.getMappedPort(12111)}",
    )

    @AfterAll
    fun stop() = mock.stop()

    private fun request(method: PaymentMethod) = CreatePaymentRequest(
        merchantRef = "acct_1Hoa", amountCents = 35_500, currency = "USD", method = method,
        idempotencyKey = "seebrook-payment-${method.code}", description = "HOA dues PAY-TEST0001",
        metadata = mapOf("seebrook_payment_id" to "00000000-0000-0000-0000-000000000001", "seebrook_reference" to "PAY-TEST0001"),
    )

    @Test
    fun `card and bank account PaymentIntents are valid Stripe API requests`() {
        PaymentMethod.entries.forEach { method ->
            val payment = stripe.createPayment(request(method))
            payment.providerPaymentId shouldStartWith "pi_"
            payment.clientParams.getValue("clientSecret") shouldStartWith "pi_"
            payment.clientParams.getValue("stripeAccount") shouldBe "acct_1Hoa"
            // stripe-mock answers with a fixture (not stateful): the request is valid and the response parses.
            stripe.lookupPayment("acct_1Hoa", payment.providerPaymentId).providerPaymentId shouldStartWith "pi_"
        }
        stripe.checkAvailable("acct_1Hoa") // a valid balance request
        // Valid list requests; stripe-mock answers with fixtures.
        val payouts = stripe.payouts("acct_1Hoa", java.time.LocalDate.parse("2026-11-01"), java.time.LocalDate.parse("2026-11-30"))
        payouts.first().payoutId shouldStartWith "po_"
        stripe.payoutLines("acct_1Hoa", payouts.first().payoutId).first().grossCents shouldBe 100
    }
}
