package cc.seebrook.app

import cc.seebrook.document.DocumentVersionJob
import cc.seebrook.document.ProcessDocumentVersion
import cc.seebrook.identity.DeliverInvitation
import cc.seebrook.identity.InvitationEmailJob
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
            if (email != null) put(InvitationEmailJob.KIND, DeliverInvitation(db, email, config.inviteLinkBase))
            if (storage != null && scanner != null) {
                put(DocumentVersionJob.KIND, ProcessDocumentVersion(db, storage, scanner, config.documentMaxBytes))
            }
        }
        return JobRunner(db, handlers)
    }
}
