package cc.seebrook.shared.api

import kotlinx.serialization.Serializable

/** Error body for every non-2xx API response. `error` is a stable machine-readable code. */
@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class AcceptInvitationRequest(val token: String)

@Serializable
data class AcceptInvitationResponse(val propertyId: String)

@Serializable
data class MeResponse(val id: String, val email: String, val displayName: String? = null)

@Serializable
data class PropertyResponse(
    val id: String,
    val hoaId: String,
    val unitLabel: String,
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val stateCode: String,
    val postalCode: String,
)

@Serializable
data class PropertiesResponse(val properties: List<PropertyResponse>)
