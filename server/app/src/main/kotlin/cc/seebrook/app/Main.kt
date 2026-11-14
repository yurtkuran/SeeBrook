package cc.seebrook.app

import cc.seebrook.app.devtools.DevSeed
import cc.seebrook.app.http.seebrookModule
import cc.seebrook.db.Migrations
import cc.seebrook.platform.config.AppConfig
import cc.seebrook.platform.config.ConfigException
import cc.seebrook.platform.db.Database
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("cc.seebrook.app")

private const val USAGE = """Usage: seebrook <command>
  serve      Start the HTTP API (default). Migrates first when SEEBROOK_MIGRATE_ON_START is true.
  migrate    Apply pending database migrations and exit.
  seed-dev   Apply migrations and load synthetic Channel Harbor data (development/test only)."""

fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "serve"
    if (command !in setOf("serve", "migrate", "seed-dev")) {
        System.err.println(USAGE)
        exitProcess(2)
    }

    val config = try {
        AppConfig.fromEnvironment()
    } catch (e: ConfigException) {
        System.err.println("Configuration error: ${e.message}")
        exitProcess(2)
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
            "serve" -> {
                if (config.migrateOnStart) migrate(db)
                embeddedServer(Netty, port = config.httpPort) { seebrookModule(db) }
                    .start(wait = true)
            }
        }
    }
}

private fun migrate(db: Database) {
    val result = Migrations.migrate(db.dataSource)
    log.info("Migrations applied: {}; schema version: {}", result.applied, result.currentVersion ?: "unchanged")
}
