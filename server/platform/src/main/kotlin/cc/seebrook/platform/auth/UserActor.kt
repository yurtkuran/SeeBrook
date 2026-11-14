package cc.seebrook.platform.auth

import java.util.UUID

/**
 * A signed-in, registered, active user, resolved from a verified token through our own user table.
 * Actions receive it explicitly and authorize against it (ED-14).
 */
data class UserActor(val userId: UUID)
