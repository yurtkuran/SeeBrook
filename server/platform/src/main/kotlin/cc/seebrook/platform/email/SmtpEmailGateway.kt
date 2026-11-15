package cc.seebrook.platform.email

import cc.seebrook.platform.config.SmtpConfig
import cc.seebrook.platform.config.SmtpSecurity
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.slf4j.LoggerFactory
import java.util.Date
import java.util.Properties
import java.util.UUID

/**
 * Delivers email over SMTP (ED-36). Every provider we plan to use accepts SMTP — Mailpit
 * locally, Brevo's relay for the beta, Postmark and Resend later — so changing provider means
 * changing the `SEEBROOK_SMTP_*` settings. A provider's HTTP API can be added later as another
 * [EmailGateway] without touching callers.
 */
class SmtpEmailGateway(private val config: SmtpConfig, from: String, replyTo: String? = null) : EmailGateway {
    private val log = LoggerFactory.getLogger(SmtpEmailGateway::class.java)
    private val fromAddress = InternetAddress(from, true)
    private val replyToAddress = replyTo?.let { InternetAddress(it, true) }

    private val session: Session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.port.toString())
            put("mail.smtp.auth", (config.username != null).toString())
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "30000")
            when (config.security) {
                SmtpSecurity.STARTTLS -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                    put("mail.smtp.ssl.checkserveridentity", "true")
                }
                SmtpSecurity.TLS -> {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.ssl.checkserveridentity", "true")
                }
                SmtpSecurity.NONE -> {}
            }
        },
    )

    override fun sendTransactional(to: Recipient, email: RenderedEmail) {
        connected { transport -> deliver(transport, to, email, bulk = false) }
    }

    override fun sendBulk(hoaId: UUID, recipients: List<Recipient>, template: EmailTemplate): BulkResult {
        require(recipients.size <= EmailGateway.MAX_BULK_RECIPIENTS) { "too many recipients for one bulk send" }
        if (recipients.isEmpty()) return BulkResult(0, emptyList())
        val failed = mutableListOf<Recipient>()
        connected { transport ->
            recipients.forEach { recipient ->
                try {
                    deliver(transport, recipient, template.render(recipient), bulk = true)
                } catch (e: MessagingException) {
                    failed += recipient
                }
            }
        }
        // Counts only: addresses are personal data.
        log.info("Bulk email for HOA {}: {} sent, {} failed", hoaId, recipients.size - failed.size, failed.size)
        return BulkResult(recipients.size - failed.size, failed)
    }

    /** One connection per call, so a bulk send does not reconnect for every recipient. */
    private fun connected(block: (Transport) -> Unit) {
        try {
            session.getTransport("smtp").use { transport ->
                transport.connect(config.host, config.port, config.username, config.password)
                block(transport)
            }
        } catch (e: MessagingException) {
            // The exception's own message can include addresses, so it is not repeated here.
            val reason = "SMTP delivery failed (${e.javaClass.simpleName})"
            throw if (isPermanent(e)) EmailRejectedException(reason, e) else EmailDeliveryException(reason, e)
        }
    }

    /** A malformed address, or a 5xx reply about the address or message: retrying cannot help. */
    private fun isPermanent(e: MessagingException): Boolean = when (e) {
        is AddressException -> true
        is SMTPAddressFailedException -> e.returnCode >= 500
        is SMTPSendFailedException -> e.returnCode >= 500 || (e.nextException as? MessagingException)?.let(::isPermanent) == true
        else -> false
    }

    private fun deliver(transport: Transport, to: Recipient, email: RenderedEmail, bulk: Boolean) {
        val message = MimeMessage(session).apply {
            setFrom(fromAddress)
            replyToAddress?.let { setReplyTo(arrayOf(it)) }
            setRecipient(
                Message.RecipientType.TO,
                InternetAddress(to.email, true).apply { to.name?.let { setPersonal(it, "UTF-8") } },
            )
            setSubject(email.subject, "UTF-8")
            sentDate = Date()
            if (email.html == null) {
                setText(email.text, "UTF-8")
            } else {
                setContent(
                    MimeMultipart("alternative").apply {
                        addBodyPart(MimeBodyPart().apply { setText(email.text, "UTF-8") })
                        addBodyPart(MimeBodyPart().apply { setText(email.html, "UTF-8", "html") })
                    },
                )
            }
            setHeader("Auto-Submitted", "auto-generated")
            if (bulk) setHeader("Precedence", "bulk")
            saveChanges()
        }
        transport.sendMessage(message, message.allRecipients)
    }
}
