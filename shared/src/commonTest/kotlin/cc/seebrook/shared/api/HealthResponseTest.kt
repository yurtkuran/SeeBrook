package cc.seebrook.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthResponseTest {
    @Test
    fun serializesWithStableWireNames() {
        val response = HealthResponse(HealthStatus.UP, mapOf("database" to HealthStatus.DOWN))

        val json = Json.encodeToString(HealthResponse.serializer(), response)

        assertEquals("""{"status":"up","checks":{"database":"down"}}""", json)
        assertEquals(response, Json.decodeFromString(HealthResponse.serializer(), json))
    }
}
