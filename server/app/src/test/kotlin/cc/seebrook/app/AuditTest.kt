package cc.seebrook.app

import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Channel
import cc.seebrook.platform.db.Database
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The audit trail is append-only at the database level (ED-33). */
class AuditTest {
    private val db: Database = TestPostgres.freshDatabase()

    @AfterEach
    fun close() = db.close()

    private fun record() = db.transaction { tx ->
        Audit.record(
            tx, actor = null, channel = Channel.SYSTEM,
            AuditEntry("test.action", null, "thing", null, "why", buildJsonObject { put("before", "a"); put("after", "b") }),
        )
    }

    @Test
    fun `records an event with its details`() {
        val id = record()
        val event = db.dsl.selectFrom(EVENT).where(EVENT.ID.eq(id)).fetchSingle()
        event.action shouldBe "test.action"
        event.channel shouldBe "system"
        event.details.data() shouldContain "\"after\""
    }

    @Test
    fun `events cannot be changed or deleted`() {
        record()
        shouldThrow<DataAccessException> { db.dsl.update(EVENT).set(EVENT.REASON, "edited").execute() }
            .message shouldContain "append-only"
        shouldThrow<DataAccessException> { db.dsl.deleteFrom(EVENT).execute() }
            .message shouldContain "append-only"
        shouldThrow<DataAccessException> { db.dsl.truncate(EVENT).execute() }
            .message shouldContain "append-only"
    }

    @Test
    fun `an audited change and its event commit or roll back together`() {
        shouldThrow<IllegalStateException> {
            db.transaction { tx ->
                Audit.record(tx, null, Channel.SYSTEM, AuditEntry("test.action", null, null, null))
                error("the change failed")
            }
        }
        db.dsl.fetchCount(EVENT) shouldBe 0
    }
}
