package cc.seebrook.property

import cc.seebrook.db.generated.property.Tables.OWNERSHIP
import org.jooq.DSLContext
import java.util.UUID
import cc.seebrook.db.generated.property.Tables.PROPERTY_ as PROPERTY

/** Property fields an administrator supplies (GA-20: the ID is assigned by us and never changes). */
data class PropertyInput(
    val unitLabel: String,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val stateCode: String,
    val postalCode: String,
) {
    /** Field problems as `field to message`; empty when valid. Values are expected trimmed. */
    fun problems(): List<Pair<String, String>> = buildList {
        fun text(field: String, value: String?, max: Int, required: Boolean = true) {
            when {
                value.isNullOrEmpty() -> if (required) add(field to "is required")
                value.length > max -> add(field to "is longer than $max characters")
            }
        }
        text("unit_label", unitLabel, 50)
        text("address_line1", addressLine1, 200)
        text("address_line2", addressLine2, 200, required = false)
        text("city", city, 100)
        if (!STATE_CODE.matches(stateCode)) add("state_code" to "must be two capital letters")
        if (!POSTAL_CODE.matches(postalCode)) add("postal_code" to "must be a 5-digit or ZIP+4 code")
    }

    private companion object {
        val STATE_CODE = Regex("^[A-Z]{2}$")
        val POSTAL_CODE = Regex("^[0-9]{5}(-[0-9]{4})?$")
    }
}

data class ExistingProperty(val id: UUID, val input: PropertyInput)

/**
 * Property operations other services call inside their own transaction (ED-13), e.g. the owner
 * import. Not endpoints: callers authorize and audit the change.
 */
object Properties {
    /** Every property of the HOA, keyed by unit label. */
    fun byUnitLabel(tx: DSLContext, hoaId: UUID): Map<String, ExistingProperty> =
        tx.selectFrom(PROPERTY).where(PROPERTY.HOA_ID.eq(hoaId)).fetch().associate {
            it.unitLabel to ExistingProperty(
                requireNotNull(it.id),
                PropertyInput(it.unitLabel, it.addressLine1, it.addressLine2, it.city, it.stateCode, it.postalCode),
            )
        }

    fun create(tx: DSLContext, hoaId: UUID, input: PropertyInput): UUID {
        require(input.problems().isEmpty()) { "invalid property input" }
        return requireNotNull(
            tx.insertInto(PROPERTY)
                .set(PROPERTY.HOA_ID, hoaId)
                .set(PROPERTY.UNIT_LABEL, input.unitLabel)
                .set(PROPERTY.ADDRESS_LINE1, input.addressLine1)
                .set(PROPERTY.ADDRESS_LINE2, input.addressLine2)
                .set(PROPERTY.CITY, input.city)
                .set(PROPERTY.STATE_CODE, input.stateCode)
                .set(PROPERTY.POSTAL_CODE, input.postalCode)
                .returning(PROPERTY.ID)
                .fetchSingle()
                .id,
        )
    }

    /** User IDs of each property's active owners in the HOA. */
    fun activeOwners(tx: DSLContext, hoaId: UUID): Map<UUID, Set<UUID>> =
        tx.select(OWNERSHIP.PROPERTY_ID, OWNERSHIP.USER_ID)
            .from(OWNERSHIP)
            .where(OWNERSHIP.HOA_ID.eq(hoaId))
            .and(OWNERSHIP.ENDED_AT.isNull)
            .fetch()
            .groupBy({ requireNotNull(it.value1()) }, { requireNotNull(it.value2()) })
            .mapValues { it.value.toSet() }
}
