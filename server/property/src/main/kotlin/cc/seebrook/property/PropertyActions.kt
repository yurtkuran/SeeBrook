package cc.seebrook.property

import cc.seebrook.db.generated.property.Tables.OWNERSHIP
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.util.UUID
import cc.seebrook.db.generated.property.Tables.PROPERTY_ as PROPERTY

data class PropertyView(
    val id: UUID,
    val hoaId: UUID,
    val unitLabel: String,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val stateCode: String,
    val postalCode: String,
)

/**
 * The single definition of owner access to a property: an active ownership row (GA-07, GA-10).
 * Matching on both HOA and property keeps every lookup HOA-scoped (GA-06).
 */
private fun activelyOwnedBy(userId: UUID): Condition =
    OWNERSHIP.HOA_ID.eq(PROPERTY.HOA_ID)
        .and(OWNERSHIP.PROPERTY_ID.eq(PROPERTY.ID))
        .and(OWNERSHIP.USER_ID.eq(userId))
        .and(OWNERSHIP.ENDED_AT.isNull)

private fun Record.toView() = PropertyView(
    id = requireNotNull(get(PROPERTY.ID)),
    hoaId = requireNotNull(get(PROPERTY.HOA_ID)),
    unitLabel = get(PROPERTY.UNIT_LABEL),
    addressLine1 = get(PROPERTY.ADDRESS_LINE1),
    addressLine2 = get(PROPERTY.ADDRESS_LINE2),
    city = get(PROPERTY.CITY),
    stateCode = get(PROPERTY.STATE_CODE),
    postalCode = get(PROPERTY.POSTAL_CODE),
)

/** Properties the actor currently owns, across all HOAs. */
class GetMyProperties(private val db: Database) {
    operator fun invoke(actor: UserActor): List<PropertyView> =
        db.dsl.select(PROPERTY.fields().toList())
            .from(PROPERTY)
            .whereExists(db.dsl.selectOne().from(OWNERSHIP).where(activelyOwnedBy(actor.userId)))
            .orderBy(PROPERTY.UNIT_LABEL, PROPERTY.ID)
            .fetch { it.toView() }
}

/** One property, or null when it does not exist *or* the actor may not see it — callers cannot tell which. */
class GetProperty(private val db: Database) {
    operator fun invoke(actor: UserActor, propertyId: UUID): PropertyView? =
        db.dsl.select(PROPERTY.fields().toList())
            .from(PROPERTY)
            .where(PROPERTY.ID.eq(propertyId))
            .andExists(db.dsl.selectOne().from(OWNERSHIP).where(activelyOwnedBy(actor.userId)))
            .fetchOne { it.toView() }
}

/**
 * Ownership operations other services call inside their own transaction (ED-13), e.g. invitation
 * acceptance. Not an endpoint: callers are responsible for authorizing the change.
 */
object Ownerships {
    /** Starts an active ownership; does nothing if one already exists. Returns true if a row was created. */
    fun grant(tx: DSLContext, hoaId: UUID, propertyId: UUID, userId: UUID): Boolean =
        tx.insertInto(OWNERSHIP)
            .set(OWNERSHIP.HOA_ID, hoaId)
            .set(OWNERSHIP.PROPERTY_ID, propertyId)
            .set(OWNERSHIP.USER_ID, userId)
            .onConflictDoNothing()
            .execute() == 1

    /**
     * True when the user actively owns at least one property in the HOA: the basis of HOA-wide
     * owner access such as the document library (GA-10). Ended ownerships grant nothing.
     */
    fun hasActivePropertyIn(tx: DSLContext, hoaId: UUID, userId: UUID): Boolean =
        tx.fetchExists(
            tx.selectOne().from(OWNERSHIP)
                .where(OWNERSHIP.HOA_ID.eq(hoaId))
                .and(OWNERSHIP.USER_ID.eq(userId))
                .and(OWNERSHIP.ENDED_AT.isNull),
        )

    /**
     * User IDs of the property's active owners. With [lock], the rows are locked until the
     * transaction ends, so a concurrent acceptance or transfer of the same unit waits.
     */
    fun activeOwners(tx: DSLContext, hoaId: UUID, propertyId: UUID, lock: Boolean = false): Set<UUID> =
        tx.select(OWNERSHIP.USER_ID).from(OWNERSHIP)
            .where(OWNERSHIP.HOA_ID.eq(hoaId))
            .and(OWNERSHIP.PROPERTY_ID.eq(propertyId))
            .and(OWNERSHIP.ENDED_AT.isNull)
            .let { if (lock) it.forUpdate() else it }
            .fetch { requireNotNull(it.value1()) }
            .toSet()

    /**
     * Ends these users' active ownership of the property now. History is kept: the row gets an
     * end time and is never deleted (GA-07). Returns how many ownerships ended.
     */
    fun end(tx: DSLContext, hoaId: UUID, propertyId: UUID, userIds: Collection<UUID>): Int =
        if (userIds.isEmpty()) {
            0
        } else {
            tx.update(OWNERSHIP)
                .set(OWNERSHIP.ENDED_AT, DSL.currentOffsetDateTime())
                .where(OWNERSHIP.HOA_ID.eq(hoaId))
                .and(OWNERSHIP.PROPERTY_ID.eq(propertyId))
                .and(OWNERSHIP.USER_ID.`in`(userIds))
                .and(OWNERSHIP.ENDED_AT.isNull)
                .execute()
        }
}
