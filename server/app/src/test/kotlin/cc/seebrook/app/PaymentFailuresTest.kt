package cc.seebrook.app

import cc.seebrook.finance.PaymentFailures
import cc.seebrook.platform.payments.PaymentMethod
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Owners see why a payment failed in words, never the provider's code (ED-64). */
class PaymentFailuresTest {
    @Test
    fun `card and bank codes become plain sentences, and fraud-related ones read as a plain decline`() {
        PaymentFailures.describe("generic_decline", PaymentMethod.CARD) shouldBe "Your card was declined. Try another card or contact your card's bank."
        PaymentFailures.describe("insufficient_funds", PaymentMethod.CARD) shouldBe "Your card was declined for insufficient funds."
        PaymentFailures.describe("expired_card", PaymentMethod.CARD) shouldBe "Your card has expired. Use another card."
        PaymentFailures.describe("insufficient_funds", PaymentMethod.ACH) shouldBe "Your bank returned the payment for insufficient funds."
        PaymentFailures.describe("account_closed", PaymentMethod.ACH) shouldBe "The bank account is closed. Use another account."
        PaymentFailures.describe(null, PaymentMethod.ACH) shouldBe "Your bank declined the payment."
        listOf("fraudulent", "stolen_card", "lost_card", "something_new").forEach {
            PaymentFailures.describe(it, PaymentMethod.CARD) shouldBe PaymentFailures.describe("generic_decline", PaymentMethod.CARD)
            PaymentFailures.describe(it, PaymentMethod.CARD) shouldNotContain "_"
        }
    }
}
