package cc.seebrook.app

import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.Migrations
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MigrationsTest {
    @Test
    fun `migrations apply to an empty database and re-running is a no-op`() {
        TestPostgres.freshDatabase(migrate = false).use { db ->
            val first = Migrations.migrate(db.dataSource)
            first.applied shouldBe 10
            first.currentVersion shouldBe "10"

            Migrations.migrate(db.dataSource).applied shouldBe 0

            val tables = db.dsl.fetch(
                "select table_schema || '.' || table_name as t from information_schema.tables " +
                    "where table_schema in ('tenant', 'property')",
            ).getValues("t", String::class.java)
            tables shouldContainAll listOf("tenant.hoa", "property.property")
        }
    }
}
