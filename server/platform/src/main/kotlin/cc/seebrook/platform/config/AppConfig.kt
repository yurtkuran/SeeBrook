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

data class AppConfig(
    val environment: Environment,
    val httpPort: Int,
    val database: DatabaseConfig,
    val migrateOnStart: Boolean,
    val auth: AuthConfig,
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

            return AppConfig(
                environment = environment,
                httpPort = port,
                database = database,
                migrateOnStart = migrateOnStart,
                auth = auth,
            )
        }
    }
}
