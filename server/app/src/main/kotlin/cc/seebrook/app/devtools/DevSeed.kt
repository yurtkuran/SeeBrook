package cc.seebrook.app.devtools

import cc.seebrook.db.generated.property.Tables.PROPERTY_ as PROPERTY
import cc.seebrook.db.generated.tenant.Tables.HOA
import cc.seebrook.platform.config.Environment
import cc.seebrook.platform.db.Database
import java.util.UUID

/**
 * Loads synthetic Channel Harbor data for local development and tests (ED-11).
 *
 * This is a development tool, not an administrative action: it refuses to run in production and
 * is idempotent, so re-running never creates duplicates. Real HOA provisioning and owner imports
 * are audited actions built in later milestones (GA-01, GA-14).
 */
object DevSeed {
    const val HOA_SLUG = "channel-harbor"
    private const val HOA_NAME = "Channel Harbor"
    private const val HOA_TIME_ZONE = "America/New_York"
    private const val FIXTURE = "/dev-fixtures/channel-harbor/properties.csv"

    data class Result(val hoaId: UUID, val propertiesCreated: Int, val totalProperties: Int)

    fun run(db: Database, environment: Environment): Result {
        check(environment == Environment.DEVELOPMENT || environment == Environment.TEST) {
            "seed-dev is only allowed in development or test, not ${environment.name.lowercase()}"
        }
        val fixture = loadFixture()

        return db.transaction { dsl ->
            dsl.insertInto(HOA)
                .set(HOA.SLUG, HOA_SLUG)
                .set(HOA.NAME, HOA_NAME)
                .set(HOA.TIME_ZONE, HOA_TIME_ZONE)
                .onConflict(HOA.SLUG).doNothing()
                .execute()
            val hoaId = requireNotNull(dsl.select(HOA.ID).from(HOA).where(HOA.SLUG.eq(HOA_SLUG)).fetchSingle(HOA.ID))

            val created = fixture.sumOf { row ->
                dsl.insertInto(PROPERTY)
                    .set(PROPERTY.HOA_ID, hoaId)
                    .set(PROPERTY.UNIT_LABEL, row.unitLabel)
                    .set(PROPERTY.ADDRESS_LINE1, row.addressLine1)
                    .set(PROPERTY.ADDRESS_LINE2, row.addressLine2)
                    .set(PROPERTY.CITY, row.city)
                    .set(PROPERTY.STATE_CODE, row.stateCode)
                    .set(PROPERTY.POSTAL_CODE, row.postalCode)
                    .onConflict(PROPERTY.HOA_ID, PROPERTY.UNIT_LABEL).doNothing()
                    .execute()
            }
            val total = dsl.fetchCount(PROPERTY, PROPERTY.HOA_ID.eq(hoaId))
            Result(hoaId, created, total)
        }
    }

    data class PropertyRow(
        val unitLabel: String,
        val addressLine1: String,
        val addressLine2: String?,
        val city: String,
        val stateCode: String,
        val postalCode: String,
    )

    private val EXPECTED_HEADER = listOf("unit_label", "address_line1", "address_line2", "city", "state_code", "postal_code")

    /** Parses the bundled synthetic fixture. Fields never contain commas, so a simple split is sufficient here. */
    fun loadFixture(): List<PropertyRow> {
        val lines = requireNotNull(DevSeed::class.java.getResourceAsStream(FIXTURE)) { "Missing fixture $FIXTURE" }
            .bufferedReader().readLines().filter { it.isNotBlank() }
        require(lines.first().split(",") == EXPECTED_HEADER) { "Unexpected fixture header in $FIXTURE" }

        val rows = lines.drop(1).mapIndexed { index, line ->
            val cols = line.split(",").map { it.trim() }
            require(cols.size == EXPECTED_HEADER.size) { "Fixture line ${index + 2} has ${cols.size} columns" }
            PropertyRow(cols[0], cols[1], cols[2].ifEmpty { null }, cols[3], cols[4], cols[5])
        }
        require(rows.map { it.unitLabel }.toSet().size == rows.size) { "Duplicate unit labels in $FIXTURE" }
        return rows
    }
}
