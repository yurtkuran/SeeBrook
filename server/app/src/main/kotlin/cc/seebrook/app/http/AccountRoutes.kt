package cc.seebrook.app.http

import cc.seebrook.identity.AcceptInvitation
import cc.seebrook.identity.GetMe
import cc.seebrook.identity.ResolveUser
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.auth.VerifiedIdentity
import cc.seebrook.platform.db.Database
import cc.seebrook.property.GetMyProperties
import cc.seebrook.property.GetProperty
import cc.seebrook.property.PropertyView
import cc.seebrook.shared.api.AcceptInvitationRequest
import cc.seebrook.shared.api.AcceptInvitationResponse
import cc.seebrook.shared.api.ErrorResponse
import cc.seebrook.shared.api.HoaLogoResponse
import cc.seebrook.shared.api.MeResponse
import cc.seebrook.shared.api.PropertiesResponse
import cc.seebrook.shared.api.PropertyResponse
import cc.seebrook.platform.storage.ObjectStorage
import cc.seebrook.tenant.FindHoa
import cc.seebrook.tenant.HoaLogoLink
import cc.seebrook.tenant.HoaView
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** The actions behind the account routes; the HTTP layer only adapts them (ED-14). */
/** [storage] null means HOA logos answer 503, like documents (ED-38). */
class AccountActions(db: Database, storage: ObjectStorage? = null) {
    val resolveUser = ResolveUser(db)
    val acceptInvitation = AcceptInvitation(db)
    val getMe = GetMe(db)
    val getMyProperties = GetMyProperties(db)
    val getProperty = GetProperty(db)
    val findHoa = FindHoa(db)
    val hoaLogoLink = storage?.let { HoaLogoLink(db, it) }

    /** Adds each property's HOA name, which the app shows with the unit (ED-51), and logo version (ED-55). */
    fun withHoaNames(properties: List<PropertyView>): List<PropertyResponse> {
        val hoas = findHoa.byIds(properties.map { it.hoaId }.toSet())
        return properties.map { it.toResponse(hoas[it.hoaId]) }
    }
}

/** JDBC calls block, so actions run on the IO dispatcher. */
internal suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

internal suspend fun RoutingCall.error(status: HttpStatusCode, code: String) = respond(status, ErrorResponse(code))

internal fun RoutingCall.identity(): VerifiedIdentity =
    checkNotNull(principal<VerifiedIdentity>()) { "route is not behind authentication" }

/** Resolves the registered, active user for this request, or responds 403 and returns null. */
internal suspend fun RoutingCall.requireUser(actions: AccountActions): UserActor? =
    when (val result = io { actions.resolveUser(identity()) }) {
        is ResolveUser.Result.Active -> result.actor
        ResolveUser.Result.NotRegistered -> null.also { error(HttpStatusCode.Forbidden, "not_registered") }
        ResolveUser.Result.Disabled -> null.also { error(HttpStatusCode.Forbidden, "account_disabled") }
    }

private fun PropertyView.toResponse(hoa: HoaView?) = PropertyResponse(
    id = id.toString(),
    hoaId = hoaId.toString(),
    hoaName = hoa?.name.orEmpty(),
    hoaLogoVersion = hoa?.logoVersion,
    unitLabel = unitLabel,
    addressLine1 = addressLine1,
    addressLine2 = addressLine2,
    city = city,
    stateCode = stateCode,
    postalCode = postalCode,
)

fun Route.accountRoutes(actions: AccountActions) {
    authenticate(FIREBASE_AUTH) {
        route("/v1") {
            // Signed in but possibly not yet registered: accepting an invitation creates the user.
            post("/invitations/accept") {
                val request = runCatching { call.receive<AcceptInvitationRequest>() }.getOrNull()
                    ?: return@post call.error(HttpStatusCode.BadRequest, "bad_request")
                when (val result = io { actions.acceptInvitation(call.identity(), request.token) }) {
                    is AcceptInvitation.Result.Accepted ->
                        call.respond(AcceptInvitationResponse(result.propertyId.toString()))
                    AcceptInvitation.Result.NotFound -> call.error(HttpStatusCode.NotFound, "invitation_not_found")
                    AcceptInvitation.Result.Expired -> call.error(HttpStatusCode.Gone, "invitation_expired")
                    AcceptInvitation.Result.NoLongerValid -> call.error(HttpStatusCode.Conflict, "invitation_no_longer_valid")
                    AcceptInvitation.Result.EmailNotVerified -> call.error(HttpStatusCode.Forbidden, "email_not_verified")
                    AcceptInvitation.Result.EmailMismatch -> call.error(HttpStatusCode.Forbidden, "invitation_email_mismatch")
                    AcceptInvitation.Result.AccountDisabled -> call.error(HttpStatusCode.Forbidden, "account_disabled")
                }
            }

            get("/me") {
                val actor = call.requireUser(actions) ?: return@get
                val me = io { actions.getMe(actor) }
                call.respond(MeResponse(me.id.toString(), me.email, me.displayName))
            }

            get("/me/properties") {
                val actor = call.requireUser(actions) ?: return@get
                val properties = io { actions.withHoaNames(actions.getMyProperties(actor)) }
                call.respond(PropertiesResponse(properties))
            }

            // 404 both for unknown properties and for properties the user may not see (GA-06).
            get("/properties/{propertyId}") {
                val actor = call.requireUser(actions) ?: return@get
                val propertyId = call.parameters["propertyId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val property = propertyId?.let { id -> io { actions.getProperty(actor, id) } }
                    ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
                call.respond(io { actions.withHoaNames(listOf(property)) }.single())
            }

            // The logo of the property's HOA (ED-55); 404 like the property itself when it may not be seen.
            get("/properties/{propertyId}/hoa-logo") {
                val actor = call.requireUser(actions) ?: return@get
                val link = actions.hoaLogoLink
                    ?: return@get call.error(HttpStatusCode.ServiceUnavailable, "logo_unavailable")
                val propertyId = call.parameters["propertyId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val property = propertyId?.let { id -> io { actions.getProperty(actor, id) } }
                    ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
                val logo = io { link(property.hoaId) } ?: return@get call.error(HttpStatusCode.NotFound, "no_logo")
                call.respond(HoaLogoResponse(logo.url.toString(), logo.expiresAt.toString(), logo.version))
            }
        }
    }
}
