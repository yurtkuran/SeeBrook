package cc.seebrook.app.cli

import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.GrantRole
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.identity.RevokeRole
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.tenant.FindHoa
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
}
