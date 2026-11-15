package cc.seebrook.app.cli

import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.GrantRole
import cc.seebrook.identity.ImportOwners
import cc.seebrook.identity.ListInvitations
import cc.seebrook.identity.ResendInvitation
import cc.seebrook.identity.RevokeInvitation
import cc.seebrook.identity.SendInvitations
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.identity.RevokeRole
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.tenant.FindHoa
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Admin command failed for a reason the operator can fix; printed without a stack trace. */
class CommandException(message: String, val exitCode: Int = 1) : RuntimeException(message)

/** Parses `--name value` options, rejecting unknown, repeated or value-less ones. */
fun parseOptions(args: List<String>, allowed: Set<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val name = args[i].removePrefix("--")
        if (!args[i].startsWith("--") || name !in allowed) throw CommandException("Unknown option '${args[i]}'", 2)
        val value = args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
            ?: throw CommandException("Option --$name needs a value", 2)
        if (options.put(name, value) != null) throw CommandException("Option --$name given twice", 2)
        i += 2
    }
    return options
}

private fun Map<String, String>.required(name: String) =
    this[name]?.takeIf { it.isNotBlank() } ?: throw CommandException("Missing --$name", 2)

/**
 * Admin commands (ED-14, ED-31): thin adapters over the same actions the API uses, so operators
 * get the same authorization and audit. Every command except `bootstrap-root` names its operator.
 */
class AdminCommands(private val db: Database) {
    private fun operator(email: String): UserActor = when (val result = ResolveOperator(db)(email)) {
        is ResolveOperator.Result.Active -> result.actor
        ResolveOperator.Result.NotFound -> throw CommandException("Operator '$email' is not a user")
        ResolveOperator.Result.Disabled -> throw CommandException("Operator '$email' is disabled")
    }

    private fun hoaId(slug: String?): UUID? = slug?.let {
        FindHoa(db).bySlug(it)?.id ?: throw CommandException("No HOA with slug '$it'")
    }

    private fun role(code: String): Role = Role.fromCode(code)
        ?: throw CommandException("Unknown role '$code'; one of ${Role.entries.joinToString { it.code }}", 2)

    fun bootstrapRoot(args: List<String>): String {
        val email = parseOptions(args, setOf("email")).required("email")
        return when (BootstrapRoot(db)(email)) {
            is BootstrapRoot.Result.Created -> "Root granted to $email"
            BootstrapRoot.Result.RootAlreadyExists ->
                throw CommandException("A Root user already exists; an existing Root must use grant-role")
        }
    }

    fun grantRole(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "user", "role", "hoa", "reason"))
        val role = role(o.required("role"))
        return when (GrantRole(db)(operator(o.required("operator")), o.required("user"), role, hoaId(o["hoa"]), o["reason"])) {
            is GrantRole.Result.Granted -> "Granted ${role.code} to ${o["user"]}"
            GrantRole.Result.AlreadyGranted -> "${o["user"]} already has ${role.code}; nothing changed"
            GrantRole.Result.Forbidden -> throw CommandException("Operator may not grant ${role.code} here")
            GrantRole.Result.UserNotFound -> throw CommandException("No user '${o["user"]}' (users are created when they accept an invitation)")
            GrantRole.Result.InvalidScope -> throw CommandException("root takes no --hoa; every other role needs --hoa", 2)
            GrantRole.Result.ReasonRequired -> throw CommandException("--reason is required (up to 1000 characters)", 2)
        }
    }

    fun revokeRole(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "user", "role", "hoa", "reason"))
        val role = role(o.required("role"))
        return when (RevokeRole(db)(operator(o.required("operator")), o.required("user"), role, hoaId(o["hoa"]), o["reason"])) {
            RevokeRole.Result.Revoked -> "Revoked ${role.code} from ${o["user"]}"
            RevokeRole.Result.NotFound -> throw CommandException("${o["user"]} has no active ${role.code} there")
            RevokeRole.Result.Forbidden -> throw CommandException("Operator may not revoke ${role.code} here")
            RevokeRole.Result.LastRoot -> throw CommandException("Cannot revoke the last Root")
            RevokeRole.Result.LastHoaAdmin -> throw CommandException("Cannot remove the HOA's last HOA Admin")
            RevokeRole.Result.InvalidScope -> throw CommandException("root takes no --hoa; every other role needs --hoa", 2)
            RevokeRole.Result.ReasonRequired -> throw CommandException("--reason is required (up to 1000 characters)", 2)
        }
    }

    fun importOwners(args: List<String>): String {
        val o = parseOptions(args.filter { it != "--commit" }, setOf("operator", "hoa", "file", "reason"))
        val commit = "--commit" in args
        val path = Path.of(o.required("file"))
        if (!Files.isRegularFile(path)) throw CommandException("No file at $path")
        if (Files.size(path) > 2L * 1024 * 1024) throw CommandException("File is too large")
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        val result = ImportOwners(db)(
            operator(o.required("operator")), hoaId, path.fileName.toString(), Files.readAllBytes(path), commit, o["reason"],
        )
        return when (result) {
            is ImportOwners.Result.Planned -> describe(result.plan, "Dry run — nothing was changed.") +
                if (result.plan.isEmpty) "" else "\nRe-run with --commit --reason <text> to apply."
            is ImportOwners.Result.Imported -> describe(result.plan, "Imported (batch ${result.importId}).")
            is ImportOwners.Result.AlreadyImported ->
                "This file was already imported at ${result.importedAt} (batch ${result.importId}); nothing changed."
            is ImportOwners.Result.Invalid -> throw CommandException(
                "File rejected; nothing was changed:\n" + result.errors.joinToString("\n") { e ->
                    val where = if (e.line == 0L) "File" else "Line ${e.line}"
                    "  $where${e.column?.let { " [$it]" }.orEmpty()}: ${e.message}"
                },
            )
            is ImportOwners.Result.Conflicts -> throw CommandException(
                "File conflicts with existing data; nothing was changed:\n" +
                    result.conflicts.joinToString("\n") { "  ${it.unitLabel}: ${it.message}" },
            )
            ImportOwners.Result.Forbidden -> throw CommandException("Operator may not manage membership in this HOA")
            ImportOwners.Result.ReasonRequired -> throw CommandException("--commit requires --reason (up to 1000 characters)", 2)
        }
    }

    fun sendInvitations(args: List<String>): String {
        val o = parseOptions(args.filter { it != "--commit" }, setOf("operator", "hoa", "unit", "reason"))
        val commit = "--commit" in args
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = SendInvitations(db)(operator(o.required("operator")), hoaId, o["unit"], commit, o["reason"])) {
            is SendInvitations.Result.Planned -> buildString {
                appendLine("Dry run — nothing was sent. Invitations that would be emailed: ${result.targets.size}")
                result.targets.forEach { appendLine("    ${it.unitLabel}: ${it.email}") }
                if (result.targets.isNotEmpty()) append("Re-run with --commit to queue these emails.")
            }.trimEnd()
            is SendInvitations.Result.Queued -> buildString {
                appendLine("Queued ${result.targets.size} invitation email${if (result.targets.size == 1) "" else "s"}; the server sends them shortly (or run run-jobs).")
                result.targets.forEach { appendLine("    ${it.unitLabel}: ${it.email}") }
            }.trimEnd()
            SendInvitations.Result.UnitNotFound -> throw CommandException("No unit '${o["unit"]}' in this HOA")
            SendInvitations.Result.Forbidden -> throw CommandException("Operator may not manage membership in this HOA")
        }
    }

    fun resendInvitation(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "unit", "email", "reason"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        val result = ResendInvitation(db)(operator(o.required("operator")), hoaId, o.required("unit"), o.required("email"), o["reason"])
        return when (result) {
            is ResendInvitation.Result.Queued ->
                "Queued a new invitation email to ${o["email"]}; its earlier link stops working once it is sent."
            ResendInvitation.Result.AlreadyQueued -> "An email to ${o["email"]} is already queued; nothing changed"
            ResendInvitation.Result.NotFound ->
                throw CommandException("No pending invitation for ${o["email"]} at ${o["unit"]} (accepted, revoked or never invited)")
            ResendInvitation.Result.Forbidden -> throw CommandException("Operator may not manage membership in this HOA")
        }
    }

    fun revokeInvitation(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "unit", "email", "reason"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        val result = RevokeInvitation(db)(operator(o.required("operator")), hoaId, o.required("unit"), o.required("email"), o["reason"])
        return when (result) {
            is RevokeInvitation.Result.Revoked -> "Revoked the invitation for ${o["email"]} at ${o["unit"]}; its link no longer works"
            RevokeInvitation.Result.NotFound ->
                throw CommandException("No pending invitation for ${o["email"]} at ${o["unit"]}")
            RevokeInvitation.Result.Forbidden -> throw CommandException("Operator may not manage membership in this HOA")
            RevokeInvitation.Result.ReasonRequired -> throw CommandException("--reason is required (up to 1000 characters)", 2)
        }
    }

    fun listInvitations(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = ListInvitations(db)(operator(o.required("operator")), hoaId)) {
            is ListInvitations.Result.Listed -> if (result.rows.isEmpty()) {
                "No invitations"
            } else {
                result.rows.joinToString("\n") { r ->
                    val sent = r.sentAt?.let { " sent $it" }.orEmpty()
                    val expires = r.expiresAt?.takeIf { r.state == ListInvitations.State.SENT }?.let { ", expires $it" }.orEmpty()
                    "${r.unitLabel}  ${r.email}  ${r.state.name.lowercase()}$sent$expires"
                }
            }
            ListInvitations.Result.Forbidden -> throw CommandException("Operator may not manage membership in this HOA")
        }
    }

    private fun describe(plan: ImportOwners.Plan, heading: String): String = buildString {
        appendLine(heading)
        appendLine("  Rows: ${plan.rowCount}; units unchanged: ${plan.unchangedUnits}")
        appendLine("  New properties: ${plan.propertiesToCreate.size}")
        plan.propertiesToCreate.forEach { appendLine("    ${it.unitLabel}, ${it.addressLine1}") }
        appendLine("  New owner invitations (sent separately): ${plan.invitationsToCreate.size}")
        plan.invitationsToCreate.forEach { appendLine("    ${it.unitLabel}: ${it.email}") }
    }.trimEnd()
}
