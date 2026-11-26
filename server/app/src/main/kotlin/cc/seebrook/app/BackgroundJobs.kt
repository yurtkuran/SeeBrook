package cc.seebrook.app

import cc.seebrook.document.DocumentVersionJob
import cc.seebrook.document.ProcessDocumentVersion
import cc.seebrook.identity.DeliverInvitation
import cc.seebrook.finance.OnOwnershipEnded
import cc.seebrook.finance.CheckUnsettledPayments
import cc.seebrook.platform.alerts.CheckJobBacklog
import cc.seebrook.finance.PaymentEmailJob
import cc.seebrook.finance.RecurringEmailJob
import cc.seebrook.finance.SendRecurringEmail
import cc.seebrook.property.OwnershipEndedJob
import cc.seebrook.finance.SendPaymentEmail
import cc.seebrook.identity.InvitationEmailJob
import cc.seebrook.identity.UserContacts
import cc.seebrook.identity.RootContacts
import cc.seebrook.platform.alerts.Alerts
import cc.seebrook.platform.alerts.SendAlertEmail
import cc.seebrook.tenant.Hoas
import cc.seebrook.platform.config.AppConfig
import cc.seebrook.platform.config.EmailConfig
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.email.BrevoEmailGateway
import cc.seebrook.platform.email.EmailGateway
import cc.seebrook.platform.email.SmtpEmailGateway
import cc.seebrook.platform.config.ScannerConfig
import cc.seebrook.platform.jobs.JobHandler
import cc.seebrook.platform.jobs.JobRunner
import cc.seebrook.platform.scan.ClamdScanner
import cc.seebrook.platform.scan.MalwareScanner
import cc.seebrook.platform.storage.ObjectStorage
import cc.seebrook.platform.storage.S3ObjectStorage

/**
 * Composition root for email, storage, scanning and background jobs (ED-35, ED-36, ED-38). The
 * only place that picks implementations; business code sees just the interfaces. A job kind whose
 * dependency is disabled gets no handler, so its jobs wait in the queue.
 */
object BackgroundJobs {
    fun emailGateway(config: AppConfig): EmailGateway? = when (val email = config.email) {
        is EmailConfig.Smtp -> SmtpEmailGateway(email.smtp, email.from, email.replyTo)
        is EmailConfig.Brevo -> BrevoEmailGateway(email.apiKey, email.from, email.replyTo, email.baseUrl)
        EmailConfig.Disabled -> null
    }

    /** Private file storage, or null when storage is disabled (ED-38). */
    fun storage(config: AppConfig): ObjectStorage? = config.storage?.let(::S3ObjectStorage)

    fun scanner(config: AppConfig): MalwareScanner? = when (val scanner = config.scanner) {
        is ScannerConfig.Clamd -> ClamdScanner(scanner.host, scanner.port)
        ScannerConfig.Disabled -> null
    }

    /**
     * Document versions are processed only when both storage and a scanner are configured;
     * otherwise uploads stay quarantined and unpublished (GA-12).
     */
    fun runner(
        db: Database,
        config: AppConfig,
        email: EmailGateway? = emailGateway(config),
        storage: ObjectStorage? = storage(config),
        scanner: MalwareScanner? = scanner(config),
    ): JobRunner {
        val handlers = buildMap<String, JobHandler> {
            // Ending an ownership cancels that owner's recurring payments for the property (GA-08).
            put(OwnershipEndedJob.KIND, OnOwnershipEnded(db))
            if (email != null) {
                put(InvitationEmailJob.KIND, DeliverInvitation(db, email, config.inviteLinkBase))
                val contacts = UserContacts(db)
                put(PaymentEmailJob.KIND, SendPaymentEmail(db, email) { contacts.find(it) })
                put(RecurringEmailJob.KIND, SendRecurringEmail(db, email) { contacts.find(it) })
                val roots = RootContacts(db)
                put(Alerts.EMAIL_KIND, SendAlertEmail(db, email, roots::all) { id -> db.transaction { Hoas.find(it, id)?.name } })
            }
            if (storage != null && scanner != null) {
                put(DocumentVersionJob.KIND, ProcessDocumentVersion(db, storage, scanner, config.documentMaxBytes))
            }
        }
        return JobRunner(db, handlers)
    }
}

/** The periodic alert checks (ED-61): payments and background jobs stuck longer than they should be. */
class AlertChecks(private val db: Database) {
    data class Found(val payments: Int, val jobs: Int)

    private val payments = CheckUnsettledPayments(db)
    private val jobs = CheckJobBacklog(db)

    fun run() = Found(payments(), jobs())
}
