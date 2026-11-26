package cc.seebrook.app

import cc.seebrook.app.cli.AlertCommands
import cc.seebrook.app.cli.CommandException
import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.audit.Tables.EVENT
import cc.seebrook.db.generated.jobs.Tables.OUTBOX_JOB
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.identity.RootContacts
import cc.seebrook.platform.alerts.Alerts
import cc.seebrook.platform.alerts.ResolveAlert
import cc.seebrook.platform.alerts.SendAlertEmail
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.email.BulkResult
import cc.seebrook.platform.email.EmailGateway
import cc.seebrook.platform.email.EmailTemplate
import cc.seebrook.platform.email.Recipient
import cc.seebrook.platform.email.RenderedEmail
import cc.seebrook.platform.jobs.JobRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Operator alerts (ED-61): one per problem, emailed to Root operators, resolved by hand or by themselves. */
class AlertsTest {
    private val db = TestPostgres.freshDatabase()
    private val root: UserActor = run {
        BootstrapRoot(db)("root@example.test")
        (ResolveOperator(db)("root@example.test") as ResolveOperator.Result.Active).actor
    }
    private val hoa = Fixtures.hoa(db, "hoa-x")
    private val sent = mutableListOf<Pair<String, RenderedEmail>>()
    private val gateway = object : EmailGateway {
        override fun sendTransactional(to: Recipient, email: RenderedEmail) {
            sent += to.email to email
        }

        override fun sendBulk(hoaId: UUID, recipients: List<Recipient>, template: EmailTemplate): BulkResult = error("not used")
    }
    private val jobs = JobRunner(db, mapOf(Alerts.EMAIL_KIND to SendAlertEmail(db, gateway, RootContacts(db)::all) { "Harbor HOA" }))
    private val cli = AlertCommands(db)

    @AfterEach
    fun close() = db.close()

    private fun raise(key: String = "payments_outage:$hoa") = db.transaction {
        Alerts.raise(it, "payments_outage", key, hoa, "Online payments paused", buildJsonObject { put("provider", "simulated") })
    }

    @Test
    fun `one alert per problem, emailed once to the Root operators with what to do`() {
        raise() shouldBe true
        raise() shouldBe false
        db.transaction { Alerts.list(it, false) }.single().occurrences shouldBe 2

        jobs.drain() shouldBe 1
        val (to, email) = sent.single()
        to shouldBe "root@example.test"
        email.subject shouldBe "[Seebrook alert] Online payments paused (Harbor HOA)"
        email.text shouldContain "What to do: Online payments for this HOA are paused automatically"
        email.text shouldContain "resolve-alert"

        db.transaction { Alerts.clear(it, "payments_outage:$hoa", "the provider answers again") } shouldBe true
        jobs.drain() shouldBe 1
        sent.last().second.subject shouldStartWith "[Seebrook] Resolved: Online payments paused"
        // A new occurrence after it cleared is a new alert, emailed again.
        raise() shouldBe true
        db.dsl.fetchCount(OUTBOX_JOB, OUTBOX_JOB.KIND.eq(Alerts.EMAIL_KIND)) shouldBe 3
    }

    @Test
    fun `only a Root operator resolves an alert, with a reason, and it is audited`() {
        raise("payment_mismatch:1")
        val id = db.transaction { Alerts.list(it, false) }.single().id
        val resolve = ResolveAlert(db)

        resolve(UserActor(UUID.randomUUID()), id, "done") shouldBe ResolveAlert.Result.Forbidden
        resolve(root, id, " ") shouldBe ResolveAlert.Result.ReasonRequired
        resolve(root, id, "Matched by hand in Stripe").shouldBeInstanceOf<ResolveAlert.Result.Resolved>().alert.resolvedBy shouldBe root.userId
        resolve(root, id, "again") shouldBe ResolveAlert.Result.AlreadyResolved
        resolve(root, UUID.randomUUID(), "x") shouldBe ResolveAlert.Result.NotFound
        db.dsl.fetchCount(EVENT, EVENT.ACTION.eq("ops.alert_resolved")) shouldBe 1
        db.transaction { Alerts.list(it, false) }.shouldBeEmpty()
    }

    @Test
    fun `the CLI lists and resolves alerts for Root operators only`() {
        cli.list(listOf("--operator", "root@example.test")) shouldBe "No open alerts."
        raise()
        val listed = cli.list(listOf("--operator", "root@example.test"))
        listed shouldContain "[OPEN] Online payments paused (HOA hoa-x)"
        listed shouldContain "What to do:"
        val id = db.transaction { Alerts.list(it, false) }.single().id

        shouldThrow<CommandException> { cli.resolve(listOf("--operator", "root@example.test", "--alert", id.toString())) }
            .message shouldContain "--reason"
        cli.resolve(listOf("--operator", "root@example.test", "--alert", id.toString(), "--reason", "Provider recovered")) shouldContain "Resolved"
        cli.list(listOf("--operator", "root@example.test")) shouldBe "No open alerts."
        cli.list(listOf("--operator", "root@example.test", "--all")) shouldContain "Resolution: Provider recovered"

        shouldThrow<CommandException> { cli.list(listOf("--operator", "nobody@example.test")) }
    }
}
