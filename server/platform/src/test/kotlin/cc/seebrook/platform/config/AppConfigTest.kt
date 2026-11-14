package cc.seebrook.platform.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class AppConfigTest {
    @Test
    fun `environment must be set explicitly`() {
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(emptyMap()) }
            .message shouldContain "SEEBROOK_ENV"
    }

    @Test
    fun `unknown environment is rejected`() {
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(mapOf("SEEBROOK_ENV" to "staging")) }
    }

    @Test
    fun `development uses local database defaults and migrates on start`() {
        val config = AppConfig.fromEnvironment(mapOf("SEEBROOK_ENV" to "development"))

        config.environment shouldBe Environment.DEVELOPMENT
        config.database.url shouldBe "jdbc:postgresql://localhost:55432/seebrook"
        config.migrateOnStart shouldBe true
        config.httpPort shouldBe 8080
    }

    @Test
    fun `production requires explicit database settings`() {
        val error = shouldThrow<ConfigException> {
            AppConfig.fromEnvironment(mapOf("SEEBROOK_ENV" to "production"))
        }
        error.message shouldContain "SEEBROOK_DB_URL"
    }

    @Test
    fun `production does not migrate on start unless asked`() {
        val config = AppConfig.fromEnvironment(
            mapOf(
                "SEEBROOK_ENV" to "production",
                "SEEBROOK_DB_URL" to "jdbc:postgresql://db/seebrook",
                "SEEBROOK_DB_USER" to "app",
                "SEEBROOK_DB_PASSWORD" to "s3cret-value",
            ),
        )
        config.migrateOnStart shouldBe false
    }

    @Test
    fun `database password is never rendered`() {
        val config = DatabaseConfig("jdbc:postgresql://db/seebrook", "app", "s3cret-value")
        config.toString() shouldNotContain "s3cret-value"
    }

    @Test
    fun `invalid port is rejected`() {
        shouldThrow<ConfigException> {
            AppConfig.fromEnvironment(mapOf("SEEBROOK_ENV" to "development", "SEEBROOK_HTTP_PORT" to "99999"))
        }
    }
}
