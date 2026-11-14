package cc.seebrook.app.support

import cc.seebrook.db.Migrations
import cc.seebrook.platform.config.DatabaseConfig
import cc.seebrook.platform.db.Database
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID

/**
 * One real PostgreSQL container per test run (ED-07); each test gets its own empty database,
 * so tests are isolated without needing Flyway clean.
 */
object TestPostgres {
    private const val IMAGE = "postgres:18-alpine"

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(IMAGE).also { it.start() }
    }

    fun freshDatabase(migrate: Boolean = true): Database {
        val name = "t_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use {
            it.createStatement().execute("CREATE DATABASE $name")
        }
        val url = "jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/$name"
        val db = Database(DatabaseConfig(url, container.username, container.password), maxPoolSize = 2)
        if (migrate) Migrations.migrate(db.dataSource)
        return db
    }
}
