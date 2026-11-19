package cc.seebrook.finance

import cc.seebrook.platform.payments.ProviderPaymentStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Fees (GA-22) and payment state changes (GA-17), ED-43. */
class PaymentRulesTest {
    @Test
    fun `the fee is a percentage of the dues, rounded half up, plus a fixed amount`() {
        FeeRule.NONE.feeFor(35_000) shouldBe 0
        FeeRule(0, 500).feeFor(35_000) shouldBe 500 // $350 dues + $5 fee
        FeeRule(300, 0).feeFor(35_000) shouldBe 1_050 // 3%
        FeeRule(290, 30).feeFor(35_000) shouldBe 1_045 // 2.9% + $0.30
        FeeRule(290, 0).feeFor(1_050) shouldBe 30 // 30.45 cents -> 30
        FeeRule(250, 0).feeFor(1_050) shouldBe 26 // 26.25 -> 26
        FeeRule(250, 0).feeFor(1_020) shouldBe 26 // 25.5 -> 26 (half up)
        FeeRule.NONE.absorbed shouldBe true
        shouldThrow<IllegalArgumentException> { FeeRule(1_001, 0) }
        shouldThrow<IllegalArgumentException> { FeeRule(0, -1) }
    }

    @Test
    fun `only valid state changes apply, and settled or canceled payments are final`() {
        val all = PaymentStatus.entries
        fun allowed(from: PaymentStatus) = all.filter { from.canBecome(it) }.toSet()
        allowed(PaymentStatus.CREATED) shouldBe setOf(PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.CANCELED)
        allowed(PaymentStatus.PROCESSING) shouldBe setOf(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.CANCELED)
        allowed(PaymentStatus.FAILED) shouldBe setOf(PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, PaymentStatus.CANCELED)
        allowed(PaymentStatus.SUCCEEDED) shouldBe emptySet()
        allowed(PaymentStatus.CANCELED) shouldBe emptySet()
        PaymentStatus.of(ProviderPaymentStatus.AWAITING_OWNER) shouldBe null
    }
}
