package cc.seebrook.app.cli

import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.audit.Audit
import cc.seebrook.platform.audit.AuditEntry
import cc.seebrook.platform.config.EmailConfig
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.email.EmailDeliveryException
import cc.seebrook.platform.email.EmailGateway
import cc.seebrook.platform.email.Recipient
import cc.seebrook.platform.email.RenderedEmail
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * `send-test-email` (ED-36, ED-62): one plain email through the configured provider to an address
 * the Root operator names, sent at once rather than queued, to check the provider and the sending
 * domain before real owners get email. Audited with the recipient's domain only.
 */
class EmailCommands(private val db: Database, private val email: EmailConfig, private val gateway: EmailGateway?) {
    fun sendTest(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "to"))
        val operator = o["operator"] ?: throw CommandException("Missing --operator", 2)
        val to = o["to"]?.trim()?.takeIf { it.contains('@') } ?: throw CommandException("--to must be an email address", 2)
        val actor = when (val result = ResolveOperator(db)(operator)) {
            is ResolveOperator.Result.Active -> result.actor
            ResolveOperator.Result.NotFound -> throw CommandException("Operator '$operator' is not a user")
            ResolveOperator.Result.Disabled -> throw CommandException("Operator '$operator' is disabled")
        }
        if (!actor.isRoot) throw CommandException("Only a Root operator can send a test email")
        val gateway = gateway ?: throw CommandException("Email is disabled: set SEEBROOK_EMAIL_MODE (smtp or brevo)")
        val mode = when (email) {
            is EmailConfig.Brevo -> "brevo"
            is EmailConfig.Smtp -> "smtp"
            EmailConfig.Disabled -> "disabled"
        }
        val sentAt = Instant.now()
        try {
            gateway.sendTransactional(
                Recipient(to),
                RenderedEmail(
                    subject = "Seebrook test email",
                    text = """
                        |This is a test email from Seebrook, sent by an operator to check email delivery.
                        |
                        |Sent: $sentAt
                        |Through: $mode
                        |
                        |If it arrived in your inbox (not spam), email for owners is set up correctly. No reply is needed.
                    """.trimMargin(),
                ),
            )
        } catch (e: EmailDeliveryException) {
            throw CommandException("Not sent: ${e.message}")
        }
        db.transaction { tx ->
            Audit.record(
                tx, actor,
                AuditEntry(
                    action = "ops.test_email_sent",
                    hoaId = null,
                    targetType = null,
                    targetId = null,
                    details = buildJsonObject {
                        put("provider", mode)
                        // The domain only: the address itself is personal data.
                        put("recipient_domain", to.substringAfterLast('@').lowercase())
                    },
                ),
            )
        }
        return "Test email accepted by $mode for $to. Check that it arrives in the inbox, not in spam."
    }
}
