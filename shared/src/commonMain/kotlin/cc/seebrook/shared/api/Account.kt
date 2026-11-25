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
    /** The HOA's display name, e.g. "Channel Harbor". */
    val hoaName: String,
    val unitLabel: String,
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val stateCode: String,
    val postalCode: String,
    /** Changes whenever the HOA's logo changes; null when the HOA has no logo (ED-55). */
    val hoaLogoVersion: String? = null,
)

/** A short-lived link to the HOA's logo, a PNG (ED-55). */
@Serializable
data class HoaLogoResponse(val url: String, val expiresAt: String, val version: String)

@Serializable
data class PropertiesResponse(val properties: List<PropertyResponse>)
