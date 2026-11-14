package cc.seebrook.app.devtools

import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.tenant.Tables.HOA
import cc.seebrook.platform.config.Environment
import cc.seebrook.platform.db.Database
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import cc.seebrook.db.generated.property.Tables.PROPERTY_ as PROPERTY

class DevSeedTest {
    private val db: Database = TestPostgres.freshDatabase()

    @AfterEach
    fun close() = db.close()

    @Test
    fun `fixture describes 32 distinct Channel Harbor units`() {
        val rows = DevSeed.loadFixture()
        rows shouldHaveSize 32
        rows.map { it.unitLabel }.toSet() shouldHaveSize 32
    }

    @Test
    fun `seed creates one HOA with 32 properties`() {
        val result = DevSeed.run(db, Environment.DEVELOPMENT)

        result.propertiesCreated shouldBe 32
        result.totalProperties shouldBe 32
        db.dsl.fetchCount(HOA) shouldBe 1
        db.dsl.fetchCount(PROPERTY) shouldBe 32
    }

    @Test
    fun `re-running the seed creates no duplicates`() {
        val first = DevSeed.run(db, Environment.DEVELOPMENT)
        val second = DevSeed.run(db, Environment.DEVELOPMENT)

        second.hoaId shouldBe first.hoaId
        second.propertiesCreated shouldBe 0
        second.totalProperties shouldBe 32
        db.dsl.fetchCount(HOA) shouldBe 1
        db.dsl.fetchCount(PROPERTY) shouldBe 32
    }

    @Test
    fun `seed refuses to run in production and writes nothing`() {
        shouldThrow<IllegalStateException> { DevSeed.run(db, Environment.PRODUCTION) }

        db.dsl.fetchCount(HOA) shouldBe 0
        db.dsl.fetchCount(PROPERTY) shouldBe 0
    }
}
