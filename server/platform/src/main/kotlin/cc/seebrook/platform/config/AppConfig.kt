package cc.seebrook.platform.config

/** Deployment environment. Must be set explicitly via `SEEBROOK_ENV`; there is no implicit default. */
enum class Environment {
    DEVELOPMENT,
    TEST,
    PRODUCTION,
    ;

    companion object {
        fun parse(value: String): Environment =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw ConfigException(
                    "SEEBROOK_ENV must be one of ${entries.joinToString { it.name.lowercase() }}",
                )
    }
}

class ConfigException(message: String) : RuntimeException(message)

class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    /** Never expose the password in logs or error messages. */
    override fun toString(): String = "DatabaseConfig(url=$url, user=$user, password=****)"
}

/** How ID tokens are verified (ED-28, ED-29). */
sealed interface AuthConfig {
    val projectId: String

    /** Firebase ID tokens signed by Google. Required in production. */
    data class Firebase(override val projectId: String) : AuthConfig

    /** Locally minted HS256 tokens (`seebrook dev-token`). Never allowed in production. */
    class Development(override val projectId: String, val secret: String) : AuthConfig {
        override fun toString(): String = "Development(projectId=$projectId, secret=****)"
    }
}

enum class SmtpSecurity { STARTTLS, TLS, NONE }

/** A provider-neutral SMTP connection (ED-36). */
class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val security: SmtpSecurity,
) {
    override fun toString(): String =
        "SmtpConfig(host=$host, port=$port, username=${username ?: "none"}, password=${if (password == null) "none" else "****"}, security=$security)"
}

/**
 * How email is delivered (ED-36). Only configuration and the composition root name providers;
 * business code sees the provider-neutral gateway.
 */
sealed interface EmailConfig {
    /** Send through an SMTP server (Mailpit locally). [from] is e.g. `Seebrook <no-reply@seebrook.cc>`. */
    data class Smtp(val smtp: SmtpConfig, val from: String, val replyTo: String?) : EmailConfig

    /** Send through Brevo's transactional HTTP API (the beta). */
    class Brevo(val apiKey: String, val from: String, val replyTo: String?, val baseUrl: String) : EmailConfig {
        override fun toString(): String = "Brevo(apiKey=****, from=$from, replyTo=$replyTo, baseUrl=$baseUrl)"
    }

    /** No delivery: email jobs stay queued until email is configured. */
    data object Disabled : EmailConfig
}

/** S3-compatible private storage (ED-38). [endpoint] is null for AWS itself. */
class StorageConfig(
    val endpoint: String?,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val pathStyle: Boolean,
) {
    override fun toString(): String =
        "StorageConfig(endpoint=$endpoint, region=$region, bucket=$bucket, accessKeyId=$accessKeyId, secretAccessKey=****, pathStyle=$pathStyle)"
}

/** Malware scanning of uploads (GA-12, ED-38). */
sealed interface ScannerConfig {
    data class Clamd(val host: String, val port: Int) : ScannerConfig

    /** No scanner: uploads stay quarantined and are never published. */
    data object Disabled : ScannerConfig
}

/** Which payment providers this deployment can use (ED-43). Each HOA's settings pick one. */
sealed interface PaymentsConfig {
    /** Stripe Connect: one platform key; each HOA's connected account is its merchant. */
    class Stripe(val secretKey: String, val publishableKey: String, val webhookSecret: String, val apiBase: String) : PaymentsConfig {
        override fun toString(): String = "Stripe(apiBase=$apiBase, keys=****)"
    }

    /** Moves no money; outcomes come from `seebrook dev-payment-event`. Never in production. */
    class Simulated(val webhookSecret: String) : PaymentsConfig {
        override fun toString(): String = "Simulated(webhookSecret=****)"
    }

    /** No payments: owners see their history, and paying is unavailable. */
    data object Disabled : PaymentsConfig
}

data class AppConfig(
    val environment: Environment,
    val httpPort: Int,
    val database: DatabaseConfig,
    val migrateOnStart: Boolean,
    val auth: AuthConfig,
    val email: EmailConfig,
    /** Invitation emails link to `<base>#token=<token>`; the fragment never reaches a server log. */
    val inviteLinkBase: String,
    /** Null when storage is disabled: document uploads and downloads are unavailable. */
    val storage: StorageConfig? = null,
    val scanner: ScannerConfig = ScannerConfig.Disabled,
    val documentMaxBytes: Int = DEFAULT_DOCUMENT_MAX_MB * 1024 * 1024,
    val payments: PaymentsConfig = PaymentsConfig.Disabled,
) {
    companion object {
        /** Local Docker Compose database (infra/local). Used only when SEEBROOK_ENV=development. */
        private const val DEV_DB_URL = "jdbc:postgresql://localhost:55432/seebrook"
        private const val DEV_DB_USER = "seebrook"
        private const val DEV_DB_PASSWORD = "seebrook-dev"

        /** Development auth defaults. The secret only protects a local, synthetic-data server. */
        const val DEV_AUTH_PROJECT_ID = "seebrook-dev"
        const val DEV_AUTH_SECRET = "seebrook-local-development-only-auth-secret"
        private val PROJECT_ID = Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$")

        /** Local Mailpit (infra/local); used by default only when SEEBROOK_ENV=development. */
        const val DEV_SMTP_HOST = "localhost"
        const val DEV_SMTP_PORT = 1025
        const val DEV_EMAIL_FROM = "Seebrook (development) <no-reply@seebrook.test>"
        const val DEFAULT_INVITE_LINK_BASE = "https://seebrook.cc/invite"

        private fun smtpEmail(env: Map<String, String>, environment: Environment): EmailConfig.Smtp {
            val isDev = environment == Environment.DEVELOPMENT
            fun value(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun required(name: String, devDefault: String) = value(name)
                ?: if (isDev) devDefault else throw ConfigException("$name is required when SEEBROOK_EMAIL_MODE is smtp")

            val security = when (val raw = value("SEEBROOK_SMTP_SECURITY")?.lowercase() ?: if (isDev) "none" else "starttls") {
                "starttls" -> SmtpSecurity.STARTTLS
                "tls" -> SmtpSecurity.TLS
                "none" -> SmtpSecurity.NONE
                else -> throw ConfigException("SEEBROOK_SMTP_SECURITY must be starttls, tls or none, not '$raw'")
            }
            if (security == SmtpSecurity.NONE && environment == Environment.PRODUCTION) {
                throw ConfigException("SEEBROOK_SMTP_SECURITY=none is not allowed in production")
            }
            val defaultPort = when {
                isDev && value("SEEBROOK_SMTP_HOST") == null -> DEV_SMTP_PORT
                security == SmtpSecurity.TLS -> 465
                security == SmtpSecurity.STARTTLS -> 587
                else -> 25
            }
            val port = value("SEEBROOK_SMTP_PORT")?.let {
                it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: throw ConfigException("SEEBROOK_SMTP_PORT must be a port number")
            } ?: defaultPort
            val username = value("SEEBROOK_SMTP_USERNAME")
            val password = env["SEEBROOK_SMTP_PASSWORD"]?.takeIf { it.isNotEmpty() }
            if ((username == null) != (password == null)) {
                throw ConfigException("SEEBROOK_SMTP_USERNAME and SEEBROOK_SMTP_PASSWORD must be set together")
            }
            val from = required("SEEBROOK_EMAIL_FROM", DEV_EMAIL_FROM)
            val replyTo = value("SEEBROOK_EMAIL_REPLY_TO")
            listOfNotNull(from, replyTo).forEach {
                if (!EMAIL_ADDRESS.matches(it)) throw ConfigException("Email sender '$it' is not a valid address")
            }
            return EmailConfig.Smtp(
                SmtpConfig(required("SEEBROOK_SMTP_HOST", DEV_SMTP_HOST), port, username, password, security),
                from,
                replyTo,
            )
        }

        private fun brevoEmail(env: Map<String, String>): EmailConfig.Brevo {
            fun value(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun required(name: String) = value(name) ?: throw ConfigException("$name is required when SEEBROOK_EMAIL_MODE is brevo")
            val apiKey = required("SEEBROOK_BREVO_API_KEY")
            val from = required("SEEBROOK_EMAIL_FROM")
            val replyTo = value("SEEBROOK_EMAIL_REPLY_TO")
            listOfNotNull(from, replyTo).forEach {
                if (!EMAIL_ADDRESS.matches(it)) throw ConfigException("Email sender '$it' is not a valid address")
            }
            val baseUrl = value("SEEBROOK_BREVO_API_URL") ?: DEFAULT_BREVO_API_URL
            if (!baseUrl.startsWith("https://")) throw ConfigException("SEEBROOK_BREVO_API_URL must be an https URL")
            return EmailConfig.Brevo(apiKey, from, replyTo, baseUrl)
        }

        const val DEFAULT_BREVO_API_URL = "https://api.brevo.com/v3"

        /** Local S3Mock and ClamAV (infra/local); used by default only when SEEBROOK_ENV=development. */
        const val DEV_S3_ENDPOINT = "http://localhost:9090"
        const val DEV_S3_BUCKET = "seebrook-dev"
        const val DEV_CLAMD_PORT = 3310
        const val DEFAULT_DOCUMENT_MAX_MB = 25

        private fun storage(env: Map<String, String>, environment: Environment): StorageConfig? {
            val isDev = environment == Environment.DEVELOPMENT
            fun value(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            val mode = value("SEEBROOK_STORAGE_MODE")?.lowercase() ?: if (isDev) "s3" else "disabled"
            if (mode == "disabled") return null
            if (mode != "s3") throw ConfigException("SEEBROOK_STORAGE_MODE must be s3 or disabled, not '$mode'")
            fun required(name: String, devDefault: String) =
                value(name) ?: if (isDev) devDefault else throw ConfigException("$name is required when SEEBROOK_STORAGE_MODE is s3")

            val endpoint = value("SEEBROOK_S3_ENDPOINT") ?: if (isDev) DEV_S3_ENDPOINT else null
            if (endpoint != null && environment == Environment.PRODUCTION && !endpoint.startsWith("https://")) {
                throw ConfigException("SEEBROOK_S3_ENDPOINT must be an https URL in production")
            }
            val pathStyle = value("SEEBROOK_S3_PATH_STYLE")?.let {
                it.toBooleanStrictOrNull() ?: throw ConfigException("SEEBROOK_S3_PATH_STYLE must be true or false")
            } ?: isDev
            return StorageConfig(
                endpoint = endpoint,
                region = required("SEEBROOK_S3_REGION", "us-east-1"),
                bucket = required("SEEBROOK_S3_BUCKET", DEV_S3_BUCKET),
                accessKeyId = required("SEEBROOK_S3_ACCESS_KEY_ID", "local"),
                secretAccessKey = env["SEEBROOK_S3_SECRET_ACCESS_KEY"]?.takeIf { it.isNotEmpty() }
                    ?: if (isDev) "local-development-only" else throw ConfigException("SEEBROOK_S3_SECRET_ACCESS_KEY is required when SEEBROOK_STORAGE_MODE is s3"),
                pathStyle = pathStyle,
            )
        }

        private fun scanner(env: Map<String, String>, environment: Environment): ScannerConfig {
            val isDev = environment == Environment.DEVELOPMENT
            fun value(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            return when (val mode = value("SEEBROOK_MALWARE_SCAN_MODE")?.lowercase() ?: if (isDev) "clamd" else "disabled") {
                "clamd" -> ScannerConfig.Clamd(
                    host = value("SEEBROOK_CLAMD_HOST") ?: if (isDev) "localhost" else throw ConfigException("SEEBROOK_CLAMD_HOST is required when SEEBROOK_MALWARE_SCAN_MODE is clamd"),
                    port = value("SEEBROOK_CLAMD_PORT")?.let {
                        it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: throw ConfigException("SEEBROOK_CLAMD_PORT must be a port number")
                    } ?: DEV_CLAMD_PORT,
                )
                "disabled" -> ScannerConfig.Disabled
                else -> throw ConfigException("SEEBROOK_MALWARE_SCAN_MODE must be clamd or disabled, not '$mode'")
            }
        }

        /** Signs simulated payment events locally; protects nothing real. */
        const val DEV_SIMULATED_WEBHOOK_SECRET = "seebrook-local-development-only-payments-secret"

        private fun payments(env: Map<String, String>, environment: Environment): PaymentsConfig {
            fun value(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            val mode = value("SEEBROOK_PAYMENTS_MODE")?.lowercase() ?: if (environment == Environment.DEVELOPMENT) "simulated" else "disabled"
            return when (mode) {
                "stripe" -> {
                    fun required(name: String, prefixes: List<String>): String {
                        val v = value(name) ?: throw ConfigException("$name is required when SEEBROOK_PAYMENTS_MODE is stripe")
                        if (prefixes.none { v.startsWith(it) }) throw ConfigException("$name does not look like a Stripe ${prefixes.joinToString("/")} key")
                        return v
                    }
                    val apiBase = value("SEEBROOK_STRIPE_API_URL") ?: "https://api.stripe.com"
                    if (!apiBase.startsWith("https://") && environment == Environment.PRODUCTION) {
                        throw ConfigException("SEEBROOK_STRIPE_API_URL must be an https URL in production")
                    }
                    PaymentsConfig.Stripe(
                        secretKey = required("SEEBROOK_STRIPE_SECRET_KEY", listOf("sk_", "rk_")),
                        publishableKey = required("SEEBROOK_STRIPE_PUBLISHABLE_KEY", listOf("pk_")),
                        webhookSecret = required("SEEBROOK_STRIPE_WEBHOOK_SECRET", listOf("whsec_")),
                        apiBase = apiBase,
                    )
                }
                "simulated" -> {
                    if (environment == Environment.PRODUCTION) throw ConfigException("SEEBROOK_PAYMENTS_MODE=simulated is not allowed in production")
                    PaymentsConfig.Simulated(value("SEEBROOK_SIMULATED_WEBHOOK_SECRET") ?: DEV_SIMULATED_WEBHOOK_SECRET)
                }
                "disabled" -> PaymentsConfig.Disabled
                else -> throw ConfigException("SEEBROOK_PAYMENTS_MODE must be stripe, simulated or disabled, not '$mode'")
            }
        }

        /** `addr@domain` or `Name <addr@domain>`. */
        private val EMAIL_ADDRESS = Regex("^([^<>]+<)?[^@<>\\s]+@[^@<>\\s]+\\.[^@<>\\s]+>?$")

        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            val environment = Environment.parse(
                env["SEEBROOK_ENV"] ?: throw ConfigException("SEEBROOK_ENV is required"),
            )
            val isDev = environment == Environment.DEVELOPMENT

            fun setting(name: String, devDefault: String): String =
                env[name]?.takeIf { it.isNotBlank() }
                    ?: if (isDev) devDefault else throw ConfigException("$name is required in ${environment.name.lowercase()}")

            val port = env["SEEBROOK_HTTP_PORT"]?.let {
                it.toIntOrNull()?.takeIf { p -> p in 1..65535 }
                    ?: throw ConfigException("SEEBROOK_HTTP_PORT must be a port number")
            } ?: 8080

            val migrateOnStart = env["SEEBROOK_MIGRATE_ON_START"]?.let {
                it.toBooleanStrictOrNull() ?: throw ConfigException("SEEBROOK_MIGRATE_ON_START must be true or false")
            } ?: isDev

            val database = DatabaseConfig(
                url = setting("SEEBROOK_DB_URL", DEV_DB_URL),
                user = setting("SEEBROOK_DB_USER", DEV_DB_USER),
                password = setting("SEEBROOK_DB_PASSWORD", DEV_DB_PASSWORD),
            )

            val auth = when (val mode = env["SEEBROOK_AUTH_MODE"]?.trim()?.lowercase()
                ?: if (environment == Environment.PRODUCTION) "firebase" else "development") {
                "firebase" -> {
                    val projectId = env["SEEBROOK_FIREBASE_PROJECT_ID"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw ConfigException("SEEBROOK_FIREBASE_PROJECT_ID is required when SEEBROOK_AUTH_MODE is firebase")
                    if (!PROJECT_ID.matches(projectId)) throw ConfigException("SEEBROOK_FIREBASE_PROJECT_ID is not a valid project ID")
                    AuthConfig.Firebase(projectId)
                }
                "development" -> {
                    if (environment == Environment.PRODUCTION) {
                        throw ConfigException("SEEBROOK_AUTH_MODE=development is not allowed in production")
                    }
                    val secret = env["SEEBROOK_DEV_AUTH_SECRET"]?.takeIf { it.isNotBlank() } ?: DEV_AUTH_SECRET
                    if (secret.toByteArray().size < 32) throw ConfigException("SEEBROOK_DEV_AUTH_SECRET must be at least 32 bytes")
                    AuthConfig.Development(DEV_AUTH_PROJECT_ID, secret)
                }
                else -> throw ConfigException("SEEBROOK_AUTH_MODE must be firebase or development, not '$mode'")
            }

            val email = when (val mode = env["SEEBROOK_EMAIL_MODE"]?.trim()?.lowercase()
                ?: if (isDev) "smtp" else "disabled") {
                "smtp" -> smtpEmail(env, environment)
                "brevo" -> brevoEmail(env)
                "disabled" -> EmailConfig.Disabled
                else -> throw ConfigException("SEEBROOK_EMAIL_MODE must be smtp, brevo or disabled, not '$mode'")
            }

            val inviteLinkBase = env["SEEBROOK_INVITE_LINK_BASE"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_INVITE_LINK_BASE
            val secureLink = inviteLinkBase.startsWith("https://")
            val localLink = inviteLinkBase.startsWith("http://localhost") || inviteLinkBase.startsWith("http://127.0.0.1")
            if ('#' in inviteLinkBase || !(secureLink || (localLink && environment != Environment.PRODUCTION))) {
                throw ConfigException("SEEBROOK_INVITE_LINK_BASE must be an https URL without a fragment")
            }

            val documentMaxMb = env["SEEBROOK_DOCUMENT_MAX_MB"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { mb -> mb in 1..100 } ?: throw ConfigException("SEEBROOK_DOCUMENT_MAX_MB must be between 1 and 100")
            } ?: DEFAULT_DOCUMENT_MAX_MB

            return AppConfig(
                environment = environment,
                httpPort = port,
                database = database,
                migrateOnStart = migrateOnStart,
                auth = auth,
                email = email,
                inviteLinkBase = inviteLinkBase,
                storage = storage(env, environment),
                scanner = scanner(env, environment),
                documentMaxBytes = documentMaxMb * 1024 * 1024,
                payments = payments(env, environment),
            )
        }
    }
}
