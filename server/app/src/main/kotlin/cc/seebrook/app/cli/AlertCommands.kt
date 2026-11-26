package cc.seebrook.app.cli

import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.alerts.Alert
import cc.seebrook.platform.alerts.AlertGuide
import cc.seebrook.platform.alerts.Alerts
import cc.seebrook.platform.alerts.ResolveAlert
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.tenant.Hoas
import java.util.UUID

/** Operator alerts (ED-61): list them and mark them handled. Root only. */
class AlertCommands(private val db: Database) {
    private fun root(email: String): UserActor {
        val actor = when (val result = ResolveOperator(db)(email)) {
            is ResolveOperator.Result.Active -> result.actor
            ResolveOperator.Result.NotFound -> throw CommandException("Operator '$email' is not a user")
            ResolveOperator.Result.Disabled -> throw CommandException("Operator '$email' is disabled")
        }
        if (!actor.isRoot) throw CommandException("Only a Root operator can see and resolve alerts")
        return actor
    }

    fun list(args: List<String>): String {
        val all = "--all" in args
        val o = parseOptions(args.filter { it != "--all" }, setOf("operator"))
        root(o["operator"] ?: throw CommandException("Missing --operator", 2))
        val alerts = db.transaction { tx -> Alerts.list(tx, includeResolved = all) }
        if (alerts.isEmpty()) return if (all) "No alerts." else "No open alerts."
        val hoaNames = db.transaction { tx ->
            alerts.mapNotNull { it.hoaId }.distinct().associateWith { Hoas.find(tx, it)?.name }
        }
        return alerts.joinToString("\n\n") { describe(it, it.hoaId?.let(hoaNames::get)) }
    }

    fun resolve(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "alert", "reason"))
        val actor = root(o["operator"] ?: throw CommandException("Missing --operator", 2))
        val id = o["alert"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw CommandException("--alert must be an alert ID from list-alerts", 2)
        return when (val result = ResolveAlert(db)(actor, id, o["reason"])) {
            is ResolveAlert.Result.Resolved -> "Resolved: ${result.alert.summary}. If the problem is still there, the next check opens a new alert."
            ResolveAlert.Result.NotFound -> throw CommandException("No alert $id")
            ResolveAlert.Result.AlreadyResolved -> throw CommandException("Alert $id is already resolved")
            ResolveAlert.Result.Forbidden -> throw CommandException("Only a Root operator can resolve alerts")
            ResolveAlert.Result.ReasonRequired -> throw CommandException("Missing --reason: say what was done", 2)
        }
    }

    private fun describe(a: Alert, hoa: String?): String = buildString {
        val state = a.resolvedAt?.let { "RESOLVED ${it.toString().take(16).replace('T', ' ')}" } ?: "OPEN"
        appendLine("[$state] ${a.summary}${hoa?.let { " ($it)" }.orEmpty()}")
        appendLine("  Alert ${a.id} · ${a.kind} · opened ${a.openedAt.toString().take(16).replace('T', ' ')} · seen ${a.occurrences} time(s)")
        a.details.forEach { (k, v) -> appendLine("  $k: ${v.toString().trim('"')}") }
        if (a.resolvedAt != null) {
            append("  Resolution: ${a.resolution ?: "-"}${if (a.resolvedBy == null) " (cleared by itself)" else ""}")
        } else {
            append("  What to do: ${AlertGuide.forKind(a.kind)}")
        }
    }
}
