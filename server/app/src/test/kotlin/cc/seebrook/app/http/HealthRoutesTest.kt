package cc.seebrook.app.http

import cc.seebrook.app.support.TestPostgres
import cc.seebrook.shared.api.HealthResponse
import cc.seebrook.shared.api.HealthStatus
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class HealthRoutesTest {
    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json() }
    }

    @Test
    fun `liveness reports up`() {
        TestPostgres.freshDatabase().use { db ->
            testApplication {
                application { seebrookModule(db) }
                val response = jsonClient().get("/health/live")

                response.status shouldBe HttpStatusCode.OK
                response.body<HealthResponse>().status shouldBe HealthStatus.UP
            }
        }
    }

    @Test
    fun `readiness reports up when the database is reachable`() {
        TestPostgres.freshDatabase().use { db ->
            testApplication {
                application { seebrookModule(db) }
                val response = jsonClient().get("/health/ready")

                response.status shouldBe HttpStatusCode.OK
                response.body<HealthResponse>() shouldBe
                    HealthResponse(HealthStatus.UP, mapOf("database" to HealthStatus.UP))
            }
        }
    }

    @Test
    fun `readiness reports 503 when the database is unavailable`() {
        val db = TestPostgres.freshDatabase()
        db.close()

        testApplication {
            application { seebrookModule(db) }
            val response = jsonClient().get("/health/ready")

            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.body<HealthResponse>().checks["database"] shouldBe HealthStatus.DOWN
        }
    }
}
