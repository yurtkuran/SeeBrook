package cc.seebrook.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of the service health endpoints. */
@Serializable
data class HealthResponse(
    val status: HealthStatus,
    val checks: Map<String, HealthStatus> = emptyMap(),
)

@Serializable
enum class HealthStatus {
    @SerialName("up")
    UP,

    @SerialName("down")
    DOWN,
}
