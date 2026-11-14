package cc.seebrook.db

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/** Applies the Flyway migrations packaged in this module (ED-04). Forward-only; clean is disabled. */
object Migrations {
    data class Result(val applied: Int, val currentVersion: String?)

    fun migrate(dataSource: DataSource): Result {
        val result = flyway(dataSource).migrate()
        return Result(applied = result.migrationsExecuted, currentVersion = result.targetSchemaVersion)
    }

    private fun flyway(dataSource: DataSource): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(true)
            .validateMigrationNaming(true)
            .load()
}
