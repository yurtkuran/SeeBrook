package cc.seebrook.app.support

import cc.seebrook.db.generated.tenant.Tables.HOA
import cc.seebrook.platform.db.Database
import java.util.UUID
import cc.seebrook.db.generated.property.Tables.PROPERTY_ as PROPERTY

/** Minimal HOA and property rows for tests that need more than the Channel Harbor seed. */
object Fixtures {
    data class Unit(val hoaId: UUID, val propertyId: UUID)

    fun hoa(db: Database, slug: String): UUID = requireNotNull(
        db.dsl.insertInto(HOA)
            .set(HOA.SLUG, slug).set(HOA.NAME, "HOA $slug").set(HOA.TIME_ZONE, "America/New_York")
            .returning(HOA.ID).fetchSingle().id,
    )

    fun property(db: Database, hoaId: UUID, unitLabel: String): Unit = Unit(
        hoaId,
        requireNotNull(
            db.dsl.insertInto(PROPERTY)
                .set(PROPERTY.HOA_ID, hoaId).set(PROPERTY.UNIT_LABEL, unitLabel)
                .set(PROPERTY.ADDRESS_LINE1, "1 Test St").set(PROPERTY.CITY, "Testville")
                .set(PROPERTY.STATE_CODE, "NC").set(PROPERTY.POSTAL_CODE, "00000")
                .returning(PROPERTY.ID).fetchSingle().id,
        ),
    )
}
