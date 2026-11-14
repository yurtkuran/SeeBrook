package cc.seebrook.app

import cc.seebrook.app.devtools.DevAuth
import cc.seebrook.app.devtools.DevSeed
import cc.seebrook.app.http.seebrookModule
import cc.seebrook.db.Migrations
import cc.seebrook.platform.auth.IdTokenVerifier
import cc.seebrook.platform.config.AppConfig
import cc.seebrook.platform.config.AuthConfig
import cc.seebrook.platform.config.ConfigException
import cc.seebrook.platform.db.Database
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("cc.seebrook.app")

private const val USAGE = """Usage: seebrook <command>
  serve                      Start the HTTP API (default). Migrates first when SEEBROOK_MIGRATE_ON_START is true.
  migrate                    Apply pending database migrations and exit.
  seed-dev                   Apply migrations and load synthetic Channel Harbor data (development/test only).
  dev-invite <unit> <email>  Issue an owner invitation for a synthetic unit and print its token (development/test only).
  dev-token <email> [--unverified]
                             Print a development ID token for <email> (development auth mode only)."""

private fun usage(): Nothing {
    System.err.println(USAGE)
    exitProcess(2)
}

fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "serve"
    val operands = args.drop(1)
    when (command) {
        "serve", "migrate", "seed-dev" -> if (operands.isNotEmpty()) usage()
        "dev-invite" -> if (operands.size != 2) usage()
        "dev-token" -> if (operands.size !in 1..2 || (operands.size == 2 && operands[1] != "--unverified")) usage()
        else -> usage()
    }

    val config = try {
        AppConfig.fromEnvironment()
    } catch (e: ConfigException) {
        System.err.println("Configuration error: ${e.message}")
        exitProcess(2)
    }

    // Needs no database.
    if (command == "dev-token") {
        println(DevAuth.token(config.environment, config.auth, operands[0], emailVerified = operands.size == 1))
        return
    }

    log.info("Starting '{}' in {} environment", command, config.environment.name.lowercase())
    Database(config.database).use { db ->
        when (command) {
            "migrate" -> migrate(db)
            "seed-dev" -> {
                migrate(db)
                val result = DevSeed.run(db, config.environment)
                log.info(
                    "Seeded HOA '{}': {} properties created, {} total",
                    DevSeed.HOA_SLUG, result.propertiesCreated, result.totalProperties,
                )
            }
            "dev-invite" -> {
                val issued = DevAuth.invite(db, config.environment, operands[0], operands[1])
                println("Invitation for ${operands[1]} to ${operands[0]} (expires ${issued.expiresAt}):")
                println(issued.token)
            }
            "serve" -> {
                if (config.migrateOnStart) migrate(db)
                val verifier = when (val auth = config.auth) {
                    is AuthConfig.Firebase -> IdTokenVerifier.firebase(auth.projectId)
                    is AuthConfig.Development -> IdTokenVerifier.development(auth.projectId, auth.secret.toByteArray())
                }
                log.info("Auth: {} (project {})", if (config.auth is AuthConfig.Firebase) "Firebase" else "development tokens", config.auth.projectId)
                embeddedServer(Netty, port = config.httpPort) { seebrookModule(db, verifier) }
                    .start(wait = true)
            }
        }
    }
}

private fun migrate(db: Database) {
    val result = Migrations.migrate(db.dataSource)
    log.info("Migrations applied: {}; schema version: {}", result.applied, result.currentVersion ?: "unchanged")
}
