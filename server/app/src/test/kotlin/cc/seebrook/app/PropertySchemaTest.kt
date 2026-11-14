package cc.seebrook.app

import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.tenant.Tables.HOA
import cc.seebrook.platform.db.Database
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import cc.seebrook.db.generated.property.Tables.PROPERTY_ as PROPERTY

/** Database-level guarantees for HOA and property identity (GA-06, GA-20). */
class PropertySchemaTest {
    private val db: Database = TestPostgres.freshDatabase()

    @AfterEach
    fun close() = db.close()

    private fun createHoa(slug: String): UUID =
        requireNotNull(
            db.dsl.insertInto(HOA)
                .set(HOA.SLUG, slug).set(HOA.NAME, "HOA $slug").set(HOA.TIME_ZONE, "America/New_York")
                .returning(HOA.ID).fetchSingle().id,
        )

    private fun createProperty(hoaId: UUID, unit: String): UUID =
        requireNotNull(
            db.dsl.insertInto(PROPERTY)
                .set(PROPERTY.HOA_ID, hoaId).set(PROPERTY.UNIT_LABEL, unit)
                .set(PROPERTY.ADDRESS_LINE1, "1 Test St").set(PROPERTY.CITY, "Testville")
                .set(PROPERTY.STATE_CODE, "NC").set(PROPERTY.POSTAL_CODE, "00000")
                .returning(PROPERTY.ID).fetchSingle().id,
        )

    @Test
    fun `unit label is unique within an HOA but may repeat across HOAs`() {
        val a = createHoa("hoa-a")
        val b = createHoa("hoa-b")
        createProperty(a, "Unit 01")

        shouldThrow<DataAccessException> { createProperty(a, "Unit 01") }
            .message shouldContain "property_unit_label_unique"
        createProperty(b, "Unit 01")
    }

    @Test
    fun `property id cannot be changed`() {
        val hoa = createHoa("hoa-a")
        val id = createProperty(hoa, "Unit 01")

        shouldThrow<DataAccessException> {
            db.dsl.update(PROPERTY).set(PROPERTY.ID, UUID.randomUUID()).where(PROPERTY.ID.eq(id)).execute()
        }.message shouldContain "immutable"
    }

    @Test
    fun `property cannot be moved to another HOA`() {
        val a = createHoa("hoa-a")
        val b = createHoa("hoa-b")
        val id = createProperty(a, "Unit 01")

        shouldThrow<DataAccessException> {
            db.dsl.update(PROPERTY).set(PROPERTY.HOA_ID, b).where(PROPERTY.ID.eq(id)).execute()
        }.message shouldContain "immutable"
        db.dsl.fetchValue(PROPERTY.HOA_ID, PROPERTY.ID.eq(id)) shouldBe a
    }

    @Test
    fun `other property fields remain editable`() {
        val hoa = createHoa("hoa-a")
        val id = createProperty(hoa, "Unit 01")

        db.dsl.update(PROPERTY).set(PROPERTY.ADDRESS_LINE2, "Suite 2").where(PROPERTY.ID.eq(id)).execute() shouldBe 1
    }

    @Test
    fun `property must reference an existing HOA`() {
        shouldThrow<DataAccessException> { createProperty(UUID.randomUUID(), "Unit 01") }
    }

    @Test
    fun `primary keys are time-ordered UUIDv7`() {
        val hoa = createHoa("hoa-a")
        // PostgreSQL guarantees UUIDv7 ordering within one session, so create both in one transaction.
        val (first, second) = db.transaction { tx ->
            fun insert(unit: String) = requireNotNull(
                tx.insertInto(PROPERTY)
                    .set(PROPERTY.HOA_ID, hoa).set(PROPERTY.UNIT_LABEL, unit)
                    .set(PROPERTY.ADDRESS_LINE1, "1 Test St").set(PROPERTY.CITY, "Testville")
                    .set(PROPERTY.STATE_CODE, "NC").set(PROPERTY.POSTAL_CODE, "00000")
                    .returning(PROPERTY.ID).fetchSingle().id,
            )
            insert("Unit 01") to insert("Unit 02")
        }

        listOf(hoa, first, second).forEach { it.version() shouldBe 7 }
        (first < second) shouldBe true
    }

    @Test
    fun `HOA-scoped reference rejects a property from another HOA`() {
        // A child table following the ED-26 pattern; real ones (e.g. ledger entries) arrive in later milestones.
        db.dsl.execute(
            """
            CREATE TABLE property.test_child (
                id          uuid PRIMARY KEY DEFAULT uuidv7(),
                hoa_id      uuid NOT NULL,
                property_id uuid NOT NULL,
                FOREIGN KEY (hoa_id, property_id) REFERENCES property.property (hoa_id, id)
            )
            """.trimIndent(),
        )
        val a = createHoa("hoa-a")
        val b = createHoa("hoa-b")
        val propertyOfA = createProperty(a, "Unit 01")
        val insertChild = "INSERT INTO property.test_child (hoa_id, property_id) VALUES (?, ?)"

        db.dsl.execute(insertChild, a, propertyOfA) shouldBe 1
        shouldThrow<DataAccessException> { db.dsl.execute(insertChild, b, propertyOfA) }
            .message shouldContain "foreign key"
    }

    @Test
    fun `HOA slug must be lowercase kebab case`() {
        shouldThrow<DataAccessException> { createHoa("Channel Harbor") }
            .message shouldContain "hoa_slug_format"
    }
}
