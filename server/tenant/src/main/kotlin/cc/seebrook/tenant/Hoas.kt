package cc.seebrook.tenant

import cc.seebrook.db.generated.tenant.Tables.HOA
import cc.seebrook.platform.db.Database
import java.util.UUID

data class HoaView(val id: UUID, val slug: String, val name: String)

/** Looks up an HOA by its slug (e.g. `channel-harbor`), as operators name HOAs in admin commands. */
class FindHoa(private val db: Database) {
    fun bySlug(slug: String): HoaView? =
        db.dsl.select(HOA.ID, HOA.SLUG, HOA.NAME).from(HOA)
            .where(HOA.SLUG.eq(slug.trim().lowercase()))
            .fetchOne { HoaView(requireNotNull(it.value1()), it.value2(), it.value3()) }
}
