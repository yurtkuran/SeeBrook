package cc.seebrook.platform.audit

import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.platform.auth.Channel
import cc.seebrook.platform.auth.UserActor
import kotlinx.serialization.json.JsonObject
import org.jooq.DSLContext
import org.jooq.JSON
import java.util.UUID

/**
 * One audit record. [details] holds before/after values and context; it must never contain
 * secrets, tokens or payment credentials.
 */
data class AuditEntry(
    val action: String,
    val hoaId: UUID?,
    val targetType: String?,
    val targetId: UUID?,
    val reason: String? = null,
    val details: JsonObject = JsonObject(emptyMap()),
)

/** Append-only audit trail (ED-33). Written in the same transaction as the change it records. */
object Audit {
    fun record(tx: DSLContext, actor: UserActor?, channel: Channel, entry: AuditEntry): UUID =
        requireNotNull(
            tx.insertInto(EVENT)
                .set(EVENT.HOA_ID, entry.hoaId)
                .set(EVENT.ACTOR_USER_ID, actor?.userId)
                .set(EVENT.CHANNEL, channel.code)
                .set(EVENT.ACTION, entry.action)
                .set(EVENT.TARGET_TYPE, entry.targetType)
                .set(EVENT.TARGET_ID, entry.targetId)
                .set(EVENT.REASON, entry.reason)
                .set(EVENT.DETAILS, JSON.valueOf(entry.details.toString()))
                .returning(EVENT.ID)
                .fetchSingle()
                .id,
        )

    fun record(tx: DSLContext, actor: UserActor, entry: AuditEntry): UUID = record(tx, actor, actor.channel, entry)
}
