package cc.seebrook.app

import cc.seebrook.identity.DeliverInvitation
import cc.seebrook.identity.InvitationEmailJob
import cc.seebrook.platform.config.AppConfig
import cc.seebrook.platform.config.EmailConfig
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.email.BrevoEmailGateway
import cc.seebrook.platform.email.EmailGateway
import cc.seebrook.platform.email.SmtpEmailGateway
import cc.seebrook.platform.jobs.JobHandler
import cc.seebrook.platform.jobs.JobRunner

/**
 * Composition root for email and background jobs (ED-35, ED-36). The only place that picks an
 * email implementation; business code sees just [EmailGateway]. Email jobs get no handler while
 * email is disabled, so they wait in the queue.
 */
object BackgroundJobs {
    fun emailGateway(config: AppConfig): EmailGateway? = when (val email = config.email) {
        is EmailConfig.Smtp -> SmtpEmailGateway(email.smtp, email.from, email.replyTo)
        is EmailConfig.Brevo -> BrevoEmailGateway(email.apiKey, email.from, email.replyTo, email.baseUrl)
        EmailConfig.Disabled -> null
    }

    fun runner(db: Database, config: AppConfig, email: EmailGateway? = emailGateway(config)): JobRunner {
        val handlers = buildMap<String, JobHandler> {
            if (email != null) put(InvitationEmailJob.KIND, DeliverInvitation(db, email, config.inviteLinkBase))
        }
        return JobRunner(db, handlers)
    }
}
