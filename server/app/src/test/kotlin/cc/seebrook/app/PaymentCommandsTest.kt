package cc.seebrook.app

import cc.seebrook.app.cli.CommandException
import cc.seebrook.app.cli.PaymentCommands
import cc.seebrook.finance.FeeRule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** How operators write per-method fees for `configure-payments` (ED-43). */
class PaymentCommandsTest {
    @Test
    fun `fees are off, zero, a percentage, dollars or both`() {
        PaymentCommands.parseFee("off") shouldBe null
        PaymentCommands.parseFee("0") shouldBe FeeRule.NONE
        PaymentCommands.parseFee("3%") shouldBe FeeRule(300, 0)
        PaymentCommands.parseFee("2.9% + 0.30") shouldBe FeeRule(290, 30)
        PaymentCommands.parseFee("$1.50") shouldBe FeeRule(0, 150)
        PaymentCommands.parseFee("0.25%") shouldBe FeeRule(25, 0)
        listOf("2.955%", "12%", "abc", "-1", "3%+").forEach { shouldThrow<CommandException> { PaymentCommands.parseFee(it) } }
        PaymentCommands.describeFee(FeeRule(290, 30)) shouldBe "owner pays 2.9% + $0.30"
        PaymentCommands.describeFee(FeeRule.NONE) shouldBe "accepted, HOA absorbs the fee"
    }
}
