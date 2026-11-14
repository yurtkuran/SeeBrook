package cc.seebrook.platform.db

import cc.seebrook.platform.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

/** Connection pool plus jOOQ entry point. All writes go through [transaction] (ED-03). */
class Database(config: DatabaseConfig, maxPoolSize: Int = 10) : AutoCloseable {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = maxPoolSize
            poolName = "seebrook"
            isAutoCommit = true
        },
    )

    val dataSource: DataSource get() = pool

    val dsl: DSLContext = DSL.using(pool, SQLDialect.POSTGRES)

    /** Runs [block] in a single database transaction; any exception rolls back every change. */
    fun <T> transaction(block: (DSLContext) -> T): T =
        dsl.transactionResult { configuration -> block(configuration.dsl()) }

    fun isReachable(): Boolean =
        runCatching { dsl.selectOne().fetchOne() != null }.getOrDefault(false)

    override fun close() = pool.close()
}
