package cc.seebrook.app.http

import cc.seebrook.platform.db.Database
import cc.seebrook.shared.api.HealthResponse
import cc.seebrook.shared.api.HealthStatus
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val log = LoggerFactory.getLogger("cc.seebrook.app.http")

@Serializable
data class ErrorResponse(val error: String)

fun Application.seebrookModule(db: Database) {
    install(ContentNegotiation) {
        json(Json { explicitNulls = false })
    }
    install(CallLogging) {
        level = Level.INFO
        // Method, path and status only — never headers, query strings or bodies.
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled error on {} {}", call.request.httpMethod.value, call.request.path(), cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
        }
    }

    routing {
        get("/health/live") {
            call.respond(HealthResponse(HealthStatus.UP))
        }
        get("/health/ready") {
            val dbUp = withContext(Dispatchers.IO) { db.isReachable() }
            val dbStatus = if (dbUp) HealthStatus.UP else HealthStatus.DOWN
            call.respond(
                if (dbUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                HealthResponse(dbStatus, mapOf("database" to dbStatus)),
            )
        }
    }
}
