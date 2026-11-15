package cc.seebrook.document

import cc.seebrook.db.generated.document.Tables.CATEGORY
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

data class CategoryView(val id: UUID, val name: String)

internal object CategoryLookup {
    /** Case-insensitive, as category names are unique regardless of case. */
    fun byName(tx: DSLContext, hoaId: UUID, name: String): CategoryView? =
        tx.select(CATEGORY.ID, CATEGORY.NAME).from(CATEGORY)
            .where(CATEGORY.HOA_ID.eq(hoaId))
            .and(DSL.lower(CATEGORY.NAME).eq(name.trim().lowercase()))
            .fetchOne { CategoryView(requireNotNull(it.value1()), it.value2()) }

    fun all(tx: DSLContext, hoaId: UUID): List<CategoryView> =
        tx.select(CATEGORY.ID, CATEGORY.NAME).from(CATEGORY)
            .where(CATEGORY.HOA_ID.eq(hoaId))
            .orderBy(DSL.lower(CATEGORY.NAME))
            .fetch { CategoryView(requireNotNull(it.value1()), it.value2()) }
}

/** Creates a single-level document category (GA-13). Names are unique per HOA, ignoring case. */
class CreateCategory(private val db: Database) {
    sealed interface Result {
        data class Created(val category: CategoryView) : Result
        data object AlreadyExists : Result
        data object InvalidName : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID, name: String): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty() || clean.length > 100) return Result.InvalidName
        return db.transaction { tx ->
            if (CategoryLookup.byName(tx, hoaId, clean) != null) return@transaction Result.AlreadyExists
            val id = requireNotNull(
                tx.insertInto(CATEGORY).set(CATEGORY.HOA_ID, hoaId).set(CATEGORY.NAME, clean)
                    .returning(CATEGORY.ID).fetchSingle().id,
            )
            Audit.record(
                tx, actor,
                AuditEntry("document.category_created", hoaId, "category", id, details = buildJsonObject { put("name", clean) }),
            )
            Result.Created(CategoryView(id, clean))
        }
    }
}

/** The HOA's categories, for document administrators. */
class ListCategories(private val db: Database) {
    sealed interface Result {
        data class Listed(val categories: List<CategoryView>) : Result
        data object Forbidden : Result
    }

    operator fun invoke(actor: UserActor, hoaId: UUID): Result {
        if (!actor.can(Permission.MANAGE_DOCUMENTS, hoaId)) return Result.Forbidden
        return Result.Listed(db.transaction { CategoryLookup.all(it, hoaId) })
    }
}
