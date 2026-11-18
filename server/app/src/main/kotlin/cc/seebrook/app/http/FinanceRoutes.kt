package cc.seebrook.app.http

import cc.seebrook.finance.GetAccountSummary
import cc.seebrook.finance.ListAccountTransactions
import cc.seebrook.platform.db.Database
import cc.seebrook.shared.api.AccountSummaryResponse
import cc.seebrook.shared.api.AccountTransactionResponse
import cc.seebrook.shared.api.AccountTransactionsPageResponse
import cc.seebrook.shared.api.CarriedForwardResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.UUID

class FinanceActions(db: Database, clock: Clock = Clock.systemUTC()) {
    val summary = GetAccountSummary(db, clock)
    val transactions = ListAccountTransactions(db)
}

/**
 * Owner access to one property's finances (ED-42). Unknown properties and properties the user does
 * not actively own answer 404 `not_found` (GA-06); an owned property without a finance account yet
 * answers 404 `finance_unavailable`.
 */
fun Route.financeRoutes(accounts: AccountActions, actions: FinanceActions) {
    authenticate(FIREBASE_AUTH) {
        route("/v1/properties/{propertyId}/finance") {
            get("/summary") {
                val actor = call.requireUser(accounts) ?: return@get
                val propertyId = call.parameters["propertyId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
                when (val result = io { actions.summary(actor, propertyId) }) {
                    is GetAccountSummary.Result.Summary -> with(result.summary) {
                        call.respond(
                            AccountSummaryResponse(
                                propertyId = propertyId.toString(),
                                currency = "USD",
                                balanceCents = balanceCents,
                                amountDueCents = amountDueCents,
                                overdueCents = overdueCents,
                                notYetDueCents = notYetDueCents,
                                creditCents = creditCents,
                                nextDueDate = nextDueDate?.toString(),
                                asOfDate = asOfDate.toString(),
                                updatedAt = updatedAt?.let(DateTimeFormatter.ISO_INSTANT::format),
                            ),
                        )
                    }
                    GetAccountSummary.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    GetAccountSummary.Result.NoAccount -> call.error(HttpStatusCode.NotFound, "finance_unavailable")
                }
            }

            get("/transactions") {
                val actor = call.requireUser(accounts) ?: return@get
                val propertyId = call.parameters["propertyId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
                val query = call.request.queryParameters
                val page = query["page"]?.let { it.toIntOrNull() ?: return@get call.error(HttpStatusCode.BadRequest, "invalid_query") } ?: 1
                val pageSize = query["pageSize"]?.let { it.toIntOrNull() ?: return@get call.error(HttpStatusCode.BadRequest, "invalid_query") }
                    ?: ListAccountTransactions.DEFAULT_PAGE_SIZE
                when (val result = io { actions.transactions(actor, propertyId, page, pageSize) }) {
                    is ListAccountTransactions.Result.Listed -> with(result.page) {
                        call.respond(
                            AccountTransactionsPageResponse(
                                transactions = transactions.map {
                                    AccountTransactionResponse(
                                        id = it.id.toString(),
                                        reference = it.reference,
                                        type = it.type.code,
                                        date = it.date.toString(),
                                        dueDate = it.dueDate?.toString(),
                                        description = it.description,
                                        amountCents = it.amountCents,
                                        balanceAfterCents = it.balanceAfterCents,
                                    )
                                },
                                carriedForward = carriedForward?.let { CarriedForwardResponse(it.amountCents, it.throughDate.toString()) },
                                page = page,
                                pageSize = pageSize,
                                total = total,
                            ),
                        )
                    }
                    ListAccountTransactions.Result.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                    ListAccountTransactions.Result.NoAccount -> call.error(HttpStatusCode.NotFound, "finance_unavailable")
                    ListAccountTransactions.Result.InvalidQuery -> call.error(HttpStatusCode.BadRequest, "invalid_query")
                }
            }
        }
    }
}
