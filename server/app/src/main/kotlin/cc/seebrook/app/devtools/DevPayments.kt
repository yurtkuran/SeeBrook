package cc.seebrook.app.devtools

import cc.seebrook.app.cli.CommandException
import cc.seebrook.app.cli.parseOptions
import cc.seebrook.db.generated.finance.Tables.PAYMENT
import cc.seebrook.platform.config.Environment
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.payments.ProviderPaymentStatus
import cc.seebrook.platform.payments.SimulatedPaymentProvider
import cc.seebrook.tenant.FindHoa
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Development tool (ED-43): plays the simulated payment provider's part against a local server. */
object DevPayments {
    /**
     * Development only: sends the simulated provider's signed webhook for a payment to a
     * running server, as the provider would after the owner paid.
     */
    fun devPaymentEvent(db: Database, environment: Environment, secret: String, args: List<String>): String {
        if (environment != Environment.DEVELOPMENT) throw CommandException("dev-payment-event is only allowed in development")
        val o = parseOptions(args, setOf("hoa", "payment", "status", "server", "failure"))
        val slug = o["hoa"] ?: throw CommandException("Missing --hoa", 2)
        val reference = o["payment"] ?: throw CommandException("Missing --payment", 2)
        val status = o["status"]?.let { s -> ProviderPaymentStatus.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
            ?: throw CommandException("--status must be processing, succeeded, failed or canceled", 2)
        val hoa = FindHoa(db).bySlug(slug)?.id ?: throw CommandException("No HOA with slug '$slug'")
        val payment = db.dsl.selectFrom(PAYMENT).where(PAYMENT.HOA_ID.eq(hoa)).and(PAYMENT.REFERENCE.eq(reference)).fetchOne()
            ?: throw CommandException("No payment $reference")
        if (payment.provider != SimulatedPaymentProvider.NAME) throw CommandException("$reference was not made with the simulated provider")
        val providerId = payment.providerPaymentId ?: throw CommandException("$reference has not reached the provider yet")
        val (body, signature) = SimulatedPaymentProvider(secret).event(
            payment.merchantRef, providerId, status, payment.totalCents, failureCode = o["failure"],
        )
        val server = (o["server"] ?: "http://localhost:8088").trimEnd('/')
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("$server/v1/webhooks/payments/${SimulatedPaymentProvider.NAME}"))
                .header("Content-Type", "application/json")
                .header(SimulatedPaymentProvider.SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return "Sent ${status.name.lowercase()} for $reference: HTTP ${response.statusCode()}"
    }
}
