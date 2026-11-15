package cc.seebrook.platform.email

import cc.seebrook.platform.jobs.PermanentFailure
import java.util.UUID

/** Someone we email. */
data class Recipient(val email: String, val name: String? = null)

/**
 * A finished email, rendered by our own templates. The body can carry a secret (an invitation
 * link), so it is never printed.
 */
class RenderedEmail(val subject: String, val text: String, val html: String? = null) {
    override fun toString(): String = "RenderedEmail(subject=$subject, body=****)"
}

/**
 * One of our email templates. Templates live in this codebase and are rendered here, so the
 * email provider never stores them and switching providers changes no content (ED-36).
 */
fun interface EmailTemplate {
    fun render(recipient: Recipient): RenderedEmail
}

/** How a bulk send went. Failed recipients can be retried by the caller. */
data class BulkResult(val sent: Int, val failed: List<Recipient>)

/**
 * The message was not accepted, for a reason that may pass (provider down, rate limit, account
 * or credentials problem): the caller retries later. Messages never contain addresses or secrets.
 */
open class EmailDeliveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The provider refused this message for good (e.g. invalid address); retrying will not help. */
class EmailRejectedException(message: String, cause: Throwable? = null) :
    EmailDeliveryException(message, cause), PermanentFailure

/**
 * The only way the application sends email (ED-36). Business code renders its own content and
 * calls one of these two methods; which provider delivers it (Mailpit locally, Brevo for the
 * beta, later Postmark or Resend) and how (SMTP or the provider's HTTP API) is configuration,
 * never business logic.
 */
interface EmailGateway {
    /**
     * One message to one person about their own account or action (invitations, receipts).
     * Throws [EmailRejectedException] if the provider refuses it for good, otherwise
     * [EmailDeliveryException] when it was not accepted.
     */
    fun sendTransactional(to: Recipient, email: RenderedEmail)

    /**
     * The same template, rendered per recipient, to members of one HOA (notices). Each recipient
     * gets their own message, so no one sees anyone else's address. A bad address fails only that
     * recipient; throws [EmailDeliveryException] only if nothing could be sent.
     */
    fun sendBulk(hoaId: UUID, recipients: List<Recipient>, template: EmailTemplate): BulkResult

    companion object {
        /** Guards against a runaway send; far above one HOA's membership. */
        const val MAX_BULK_RECIPIENTS = 1000
    }
}
