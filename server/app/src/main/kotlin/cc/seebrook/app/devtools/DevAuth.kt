package cc.seebrook.app.devtools

import cc.seebrook.db.generated.tenant.Tables.HOA
import cc.seebrook.identity.IssueInvitation
import cc.seebrook.platform.config.AuthConfig
import cc.seebrook.platform.config.Environment
import cc.seebrook.platform.auth.DevTokens
import cc.seebrook.platform.db.Database
import java.time.Duration
import cc.seebrook.db.generated.property.Tables.PROPERTY_ as PROPERTY

/**
 * Development helpers for exercising sign-in locally without email delivery or a Firebase
 * project (ED-29). Both refuse to run outside development/test.
 */
object DevAuth {
    private fun requireNonProduction(environment: Environment, command: String) =
        check(environment != Environment.PRODUCTION) { "$command is only allowed in development or test" }

    /** Issues an invitation for a synthetic Channel Harbor unit and returns it (token included). */
    fun invite(db: Database, environment: Environment, unitLabel: String, email: String): IssueInvitation.Issued {
        requireNonProduction(environment, "dev-invite")
        val property = db.dsl.select(PROPERTY.HOA_ID, PROPERTY.ID)
            .from(PROPERTY).join(HOA).on(HOA.ID.eq(PROPERTY.HOA_ID))
            .where(HOA.SLUG.eq(DevSeed.HOA_SLUG)).and(PROPERTY.UNIT_LABEL.eq(unitLabel))
            .fetchOne() ?: throw IllegalArgumentException("No unit '$unitLabel' in ${DevSeed.HOA_SLUG}; run seed-dev first")
        return IssueInvitation(db)(requireNotNull(property.value1()), requireNotNull(property.value2()), email)
    }

    /** Mints a development ID token for [email]; only a server in development auth mode accepts it. */
    fun token(environment: Environment, auth: AuthConfig, email: String, emailVerified: Boolean, validFor: Duration = Duration.ofHours(1)): String {
        requireNonProduction(environment, "dev-token")
        check(auth is AuthConfig.Development) { "dev-token requires SEEBROOK_AUTH_MODE=development" }
        return DevTokens.mint(auth.projectId, auth.secret.toByteArray(), email, emailVerified, validFor)
    }
}
