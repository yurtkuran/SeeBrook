package cc.seebrook.platform.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
                "SEEBROOK_FIREBASE_PROJECT_ID" to "seebrook-prod",
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

    private val productionDb = mapOf(
        "SEEBROOK_ENV" to "production",
        "SEEBROOK_DB_URL" to "jdbc:postgresql://db/seebrook",
        "SEEBROOK_DB_USER" to "app",
        "SEEBROOK_DB_PASSWORD" to "s3cret-value",
    )

    @Test
    fun `development defaults to development auth`() {
        val config = AppConfig.fromEnvironment(mapOf("SEEBROOK_ENV" to "development"))
        config.auth.shouldBeInstanceOf<AuthConfig.Development>().projectId shouldBe "seebrook-dev"
    }

    @Test
    fun `production uses Firebase auth with an explicit project`() {
        val config = AppConfig.fromEnvironment(productionDb + ("SEEBROOK_FIREBASE_PROJECT_ID" to "seebrook-prod"))
        config.auth shouldBe AuthConfig.Firebase("seebrook-prod")
    }

    @Test
    fun `production requires a Firebase project ID`() {
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(productionDb) }
            .message shouldContain "SEEBROOK_FIREBASE_PROJECT_ID"
    }

    @Test
    fun `production refuses development auth`() {
        shouldThrow<ConfigException> {
            AppConfig.fromEnvironment(productionDb + ("SEEBROOK_AUTH_MODE" to "development"))
        }.message shouldContain "not allowed in production"
    }

    @Test
    fun `development auth secret is never rendered`() {
        val config = AppConfig.fromEnvironment(
            mapOf("SEEBROOK_ENV" to "development", "SEEBROOK_DEV_AUTH_SECRET" to "x".repeat(40)),
        )
        config.auth.toString() shouldNotContain "x".repeat(40)
    }

    @Test
    fun `development email goes to the local Mailpit and production email is off until configured`() {
        val dev = AppConfig.fromEnvironment(mapOf("SEEBROOK_ENV" to "development")).email.shouldBeInstanceOf<EmailConfig.Smtp>()
        dev.smtp.host shouldBe "localhost"
        dev.smtp.port shouldBe 1025
        dev.smtp.security shouldBe SmtpSecurity.NONE
        AppConfig.fromEnvironment(productionDb + ("SEEBROOK_FIREBASE_PROJECT_ID" to "seebrook-prod")).email shouldBe
            EmailConfig.Disabled
        AppConfig.fromEnvironment(productionDb + ("SEEBROOK_ENV" to "test")).email shouldBe EmailConfig.Disabled
    }

    @Test
    fun `production SMTP needs explicit settings and encryption, and hides the password`() {
        val prod = productionDb + mapOf("SEEBROOK_FIREBASE_PROJECT_ID" to "seebrook-prod", "SEEBROOK_EMAIL_MODE" to "smtp")
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(prod) }.message shouldContain "SEEBROOK_EMAIL_FROM"

        val relay = prod + mapOf(
            "SEEBROOK_EMAIL_FROM" to "Seebrook <no-reply@seebrook.cc>",
            "SEEBROOK_SMTP_HOST" to "smtp.example.test",
            "SEEBROOK_SMTP_USERNAME" to "user",
            "SEEBROOK_SMTP_PASSWORD" to "smtp-secret-value",
        )
        val email = AppConfig.fromEnvironment(relay).email.shouldBeInstanceOf<EmailConfig.Smtp>()
        email.smtp.security shouldBe SmtpSecurity.STARTTLS
        email.smtp.port shouldBe 587
        email.toString() shouldNotContain "smtp-secret-value"

        shouldThrow<ConfigException> { AppConfig.fromEnvironment(relay + ("SEEBROOK_SMTP_SECURITY" to "none")) }
            .message shouldContain "not allowed in production"
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(relay - "SEEBROOK_SMTP_PASSWORD") }
            .message shouldContain "set together"
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(relay + ("SEEBROOK_EMAIL_FROM" to "not an address")) }
        AppConfig.fromEnvironment(relay + ("SEEBROOK_SMTP_SECURITY" to "tls")).email
            .shouldBeInstanceOf<EmailConfig.Smtp>().smtp.port shouldBe 465
    }

    @Test
    fun `invitation links must be https, except localhost outside production`() {
        val dev = mapOf("SEEBROOK_ENV" to "development")
        AppConfig.fromEnvironment(dev).inviteLinkBase shouldBe "https://seebrook.cc/invite"
        AppConfig.fromEnvironment(dev + ("SEEBROOK_INVITE_LINK_BASE" to "http://localhost:8080/invite"))
            .inviteLinkBase shouldBe "http://localhost:8080/invite"
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(dev + ("SEEBROOK_INVITE_LINK_BASE" to "http://example.test/invite")) }
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(dev + ("SEEBROOK_INVITE_LINK_BASE" to "https://seebrook.cc/invite#x")) }
        shouldThrow<ConfigException> {
            AppConfig.fromEnvironment(
                productionDb + mapOf(
                    "SEEBROOK_FIREBASE_PROJECT_ID" to "seebrook-prod",
                    "SEEBROOK_INVITE_LINK_BASE" to "http://localhost:8080/invite",
                ),
            )
        }
    }

    @Test
    fun `Brevo mode needs an API key and sender, and hides the key`() {
        val prod = productionDb + mapOf("SEEBROOK_FIREBASE_PROJECT_ID" to "seebrook-prod", "SEEBROOK_EMAIL_MODE" to "brevo")
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(prod) }.message shouldContain "SEEBROOK_BREVO_API_KEY"

        val brevo = prod + mapOf("SEEBROOK_BREVO_API_KEY" to "xkeysib-secret-value", "SEEBROOK_EMAIL_FROM" to "Seebrook <no-reply@seebrook.cc>")
        val email = AppConfig.fromEnvironment(brevo).email.shouldBeInstanceOf<EmailConfig.Brevo>()
        email.baseUrl shouldBe "https://api.brevo.com/v3"
        email.toString() shouldNotContain "xkeysib-secret-value"
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(brevo + ("SEEBROOK_BREVO_API_URL" to "http://api.example.test")) }
    }

    @Test
    fun `development uses local S3Mock and ClamAV while elsewhere storage and scanning are off until configured`() {
        val dev = AppConfig.fromEnvironment(mapOf("SEEBROOK_ENV" to "development"))
        requireNotNull(dev.storage).endpoint shouldBe "http://localhost:9090"
        dev.scanner shouldBe ScannerConfig.Clamd("localhost", 3310)
        dev.documentMaxBytes shouldBe 25 * 1024 * 1024

        val prod = AppConfig.fromEnvironment(productionDb + ("SEEBROOK_FIREBASE_PROJECT_ID" to "seebrook-prod"))
        prod.storage shouldBe null
        prod.scanner shouldBe ScannerConfig.Disabled
    }

    @Test
    fun `production storage needs its settings and https, and hides the secret key`() {
        val prod = productionDb + mapOf("SEEBROOK_FIREBASE_PROJECT_ID" to "seebrook-prod", "SEEBROOK_STORAGE_MODE" to "s3")
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(prod) }.message shouldContain "SEEBROOK_S3_REGION"

        val s3 = prod + mapOf(
            "SEEBROOK_S3_REGION" to "auto",
            "SEEBROOK_S3_BUCKET" to "seebrook-prod",
            "SEEBROOK_S3_ACCESS_KEY_ID" to "key-id",
            "SEEBROOK_S3_SECRET_ACCESS_KEY" to "s3-secret-value",
            "SEEBROOK_S3_ENDPOINT" to "https://storage.example.test",
        )
        val storage = requireNotNull(AppConfig.fromEnvironment(s3).storage)
        storage.pathStyle shouldBe false
        storage.toString() shouldNotContain "s3-secret-value"
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(s3 + ("SEEBROOK_S3_ENDPOINT" to "http://storage.example.test")) }
        shouldThrow<ConfigException> { AppConfig.fromEnvironment(s3 + ("SEEBROOK_DOCUMENT_MAX_MB" to "0")) }
    }
}
