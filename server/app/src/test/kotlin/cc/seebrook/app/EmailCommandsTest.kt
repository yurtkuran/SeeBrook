package cc.seebrook.app

import cc.seebrook.app.cli.CommandException
import cc.seebrook.app.cli.EmailCommands
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.platform.config.EmailConfig
import cc.seebrook.platform.email.BulkResult
import cc.seebrook.platform.email.EmailGateway
import cc.seebrook.platform.email.EmailRejectedException
import cc.seebrook.platform.email.EmailTemplate
import cc.seebrook.platform.email.Recipient
import cc.seebrook.platform.email.RenderedEmail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** `send-test-email` (ED-62): Root only, sent at once, audited without the address. */
class EmailCommandsTest {
    private val db = TestPostgres.freshDatabase()
    private val sent = mutableListOf<Pair<String, RenderedEmail>>()
    private var reject = false
    private val gateway = object : EmailGateway {
        override fun sendTransactional(to: Recipient, email: RenderedEmail) {
            if (reject) throw EmailRejectedException("Brevo refused the message (400)")
            sent += to.email to email
        }

        override fun sendBulk(hoaId: UUID, recipients: List<Recipient>, template: EmailTemplate): BulkResult = error("not used")
    }
    private val brevo = EmailConfig.Brevo("key", "Seebrook <no-reply@seebrook.cc>", null, "https://api.example.test")

    init {
        BootstrapRoot(db)("root@example.test")
    }

    @AfterEach
    fun close() = db.close()

    private fun send(to: String, gateway: EmailGateway? = this.gateway, operator: String = "root@example.test") =
        EmailCommands(db, brevo, gateway).sendTest(listOf("--operator", operator, "--to", to))

    @Test
    fun `a Root operator sends one test email and the audit keeps only the domain`() {
        send("Pat@Example.org") shouldContain "accepted by brevo"
        sent.single().first shouldBe "Pat@Example.org"
        sent.single().second.subject shouldBe "Seebrook test email"
        val details = db.dsl.select(EVENT.DETAILS).from(EVENT).where(EVENT.ACTION.eq("ops.test_email_sent")).fetchSingle().value1().data()
        details shouldContain "example.org"
        details shouldNotContain "pat@"
    }

    @Test
    fun `it is refused for non-Root operators, without email configured, and reports a rejection`() {
        shouldThrow<CommandException> { send("a@example.org", operator = "nobody@example.test") }
        shouldThrow<CommandException> { send("a@example.org", gateway = null) }.message shouldContain "SEEBROOK_EMAIL_MODE"
        shouldThrow<CommandException> { send("not-an-address") }
        reject = true
        shouldThrow<CommandException> { send("a@example.org") }.message shouldContain "Not sent"
        db.dsl.fetchCount(EVENT, EVENT.ACTION.eq("ops.test_email_sent")) shouldBe 0
        sent.size shouldBe 0
    }
}
