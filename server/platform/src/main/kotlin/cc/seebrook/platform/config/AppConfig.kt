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

data class AppConfig(
    val environment: Environment,
    val httpPort: Int,
    val database: DatabaseConfig,
    val migrateOnStart: Boolean,
) {
    companion object {
        /** Local Docker Compose database (infra/local). Used only when SEEBROOK_ENV=development. */
        private const val DEV_DB_URL = "jdbc:postgresql://localhost:55432/seebrook"
        private const val DEV_DB_USER = "seebrook"
        private const val DEV_DB_PASSWORD = "seebrook-dev"

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

            return AppConfig(
                environment = environment,
                httpPort = port,
                database = DatabaseConfig(
                    url = setting("SEEBROOK_DB_URL", DEV_DB_URL),
                    user = setting("SEEBROOK_DB_USER", DEV_DB_USER),
                    password = setting("SEEBROOK_DB_PASSWORD", DEV_DB_PASSWORD),
                ),
                migrateOnStart = migrateOnStart,
            )
        }
    }
}
