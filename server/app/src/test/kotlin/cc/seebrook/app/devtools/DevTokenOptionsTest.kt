package cc.seebrook.app.devtools

import cc.seebrook.app.devTokenOptions
import cc.seebrook.platform.config.AuthConfig
import cc.seebrook.platform.config.Environment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** `dev-token` options: longer-lived tokens for testing on a phone, still development only (ED-29, ED-48). */
class DevTokenOptionsTest {
    @Test
    fun `tokens last an hour unless days are given, up to 30`() {
        devTokenOptions(listOf("a@example.test")) shouldBe (true to Duration.ofHours(1))
        devTokenOptions(listOf("a@example.test", "--days", "7")) shouldBe (true to Duration.ofDays(7))
        devTokenOptions(listOf("a@example.test", "--unverified", "--days", "30")) shouldBe (false to Duration.ofDays(30))
        listOf(
            emptyList(), listOf("--days", "7"), listOf("a@example.test", "--days"), listOf("a@example.test", "--days", "0"),
            listOf("a@example.test", "--days", "31"), listOf("a@example.test", "--days", "x"), listOf("a@example.test", "extra"),
        ).forEach { devTokenOptions(it) shouldBe null }
    }

    @Test
    fun `development tokens are refused in production`() {
        val auth = AuthConfig.Development("seebrook-dev", "x".repeat(32))
        shouldThrow<IllegalStateException> { DevAuth.token(Environment.PRODUCTION, auth, "a@example.test", true, Duration.ofDays(7)) }
    }
}
