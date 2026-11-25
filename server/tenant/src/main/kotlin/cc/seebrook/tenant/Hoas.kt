package cc.seebrook.tenant

import cc.seebrook.db.generated.tenant.Tables.HOA
import cc.seebrook.platform.db.Database
import org.jooq.DSLContext
import java.util.UUID

/** [logoVersion] and [photoVersion] are null when the HOA has no logo (ED-55) or photo (ED-57). */
data class HoaView(
    val id: UUID,
    val slug: String,
    val name: String,
    val timeZone: String,
    val logoVersion: String? = null,
    val photoVersion: String? = null,
)

/** Looks up an HOA by its slug (e.g. `channel-harbor`), as operators name HOAs in admin commands. */
class FindHoa(private val db: Database) {
    fun bySlug(slug: String): HoaView? =
        db.dsl.select(HOA.ID, HOA.SLUG, HOA.NAME, HOA.TIME_ZONE).from(HOA)
            .where(HOA.SLUG.eq(slug.trim().lowercase()))
            .fetchOne { HoaView(requireNotNull(it.value1()), it.value2(), it.value3(), it.value4()) }

    /** The HOAs with these IDs, by ID; unknown IDs are left out. */
    fun byIds(ids: Collection<UUID>): Map<UUID, HoaView> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            db.dsl.select(HOA.ID, HOA.SLUG, HOA.NAME, HOA.TIME_ZONE, HOA.LOGO_KEY, HOA.PHOTO_KEY).from(HOA)
                .where(HOA.ID.`in`(ids))
                .fetch {
                    HoaView(
                        requireNotNull(it.value1()), it.value2(), it.value3(), it.value4(),
                        logoVersion = it.value5()?.let(HoaImageKeys::version),
                        photoVersion = it.value6()?.let(HoaImageKeys::version),
                    )
                }
                .associateBy { it.id }
        }
}

/** HOA lookups other services make inside their own transaction (ED-13). */
object Hoas {
    fun find(tx: DSLContext, id: UUID): HoaView? =
        tx.select(HOA.ID, HOA.SLUG, HOA.NAME, HOA.TIME_ZONE).from(HOA)
            .where(HOA.ID.eq(id))
            .fetchOne { HoaView(requireNotNull(it.value1()), it.value2(), it.value3(), it.value4()) }
}
