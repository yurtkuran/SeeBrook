package cc.seebrook.platform.auth

import java.util.UUID

/** Where an action was invoked from; recorded in the audit trail. */
enum class Channel(val code: String) {
    API("api"),
    CLI("cli"),
    SYSTEM("system"),
}

/** What a role allows inside its HOA (ED-32). Root holds every permission in every HOA. */
enum class Permission {
    MANAGE_ROLES,
    MANAGE_MEMBERSHIP,
    MANAGE_DOCUMENTS,
    MANAGE_FINANCES,

    /** The HOA's own details shown to owners, such as its logo (ED-55). */
    MANAGE_HOA_PROFILE,
}

/** Roles from GA-03. All but Root are scoped to one HOA and combinable. */
enum class Role(val code: String, val permissions: Set<Permission>) {
    ROOT("root", Permission.entries.toSet()),
    HOA_ADMIN("hoa_admin", setOf(Permission.MANAGE_ROLES, Permission.MANAGE_MEMBERSHIP, Permission.MANAGE_HOA_PROFILE)),
    MEMBERSHIP_ADMIN("membership_admin", setOf(Permission.MANAGE_MEMBERSHIP)),
    DOCUMENT_ADMIN("document_admin", setOf(Permission.MANAGE_DOCUMENTS)),
    FINANCIAL_ADMIN("financial_admin", setOf(Permission.MANAGE_FINANCES)),
    ;

    val isHoaScoped: Boolean get() = this != ROOT

    companion object {
        fun fromCode(code: String): Role? = entries.firstOrNull { it.code == code.trim().lowercase() }
    }
}

/** An active role assignment; [hoaId] is null only for Root. */
data class RoleGrant(val role: Role, val hoaId: UUID?)

/**
 * A registered, active user resolved from our database for one request or command, with their
 * active roles at that moment. Actions receive it explicitly and authorize against it (ED-14).
 */
data class UserActor(
    val userId: UUID,
    val roles: Set<RoleGrant> = emptySet(),
    val channel: Channel = Channel.API,
) {
    val isRoot: Boolean get() = roles.any { it.role == Role.ROOT }

    fun can(permission: Permission, hoaId: UUID): Boolean =
        isRoot || roles.any { it.hoaId == hoaId && permission in it.role.permissions }
}
