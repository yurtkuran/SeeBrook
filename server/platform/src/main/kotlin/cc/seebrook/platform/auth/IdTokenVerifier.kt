package cc.seebrook.platform.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSKeySelector
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.proc.SingleKeyJWSKeySelector
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI
import java.text.ParseException
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * An identity proven by a verified ID token. It says who signed in, not what they may access:
 * authorization always comes from our database (ED-28).
 */
data class VerifiedIdentity(
    /** Our name for the identity provider, stored with [subject] in `identity.external_identity`. */
    val provider: String,
    /** The provider's account ID (Firebase UID). */
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val name: String?,
    /** When the user last actively signed in; used for sensitive-action reauthentication (GA-04). */
    val authTime: Instant,
    /** How the user signed in, e.g. `password`, `google.com`, `apple.com`. */
    val signInMethod: String?,
)

/** The token is missing, malformed, forged, expired or meant for another project. */
class InvalidTokenException(message: String) : RuntimeException(message)

/** The signing keys could not be fetched, so no token can be verified right now. */
class TokenKeysUnavailableException(cause: Throwable) : RuntimeException("ID token signing keys are unavailable", cause)

/**
 * Verifies Firebase ID tokens locally (ED-28): signature, issuer, audience, expiry and sign-in time.
 * The Firebase Admin SDK is not needed. Development mode uses the same checks with a shared
 * HMAC key instead of Google's keys, so local runs and production follow one code path.
 */
class IdTokenVerifier private constructor(
    projectId: String,
    keySelector: JWSKeySelector<SecurityContext>,
) {
    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = keySelector
        jwtClaimsSetVerifier = DefaultJWTClaimsVerifier<SecurityContext>(
            projectId,
            JWTClaimsSet.Builder().issuer(issuerFor(projectId)).build(),
            setOf("sub", "iat", "exp", "auth_time"),
        ).apply { maxClockSkew = MAX_CLOCK_SKEW_SECONDS }
    }

    /** @throws InvalidTokenException if the token is not acceptable. */
    fun verify(token: String): VerifiedIdentity {
        if (token.length > MAX_TOKEN_LENGTH) throw InvalidTokenException("token too long")
        val claims = try {
            processor.process(token, null)
        } catch (e: KeySourceException) {
            throw TokenKeysUnavailableException(e)
        } catch (e: BadJOSEException) {
            throw InvalidTokenException(e.message ?: "rejected")
        } catch (e: JOSEException) {
            throw InvalidTokenException(e.message ?: "rejected")
        } catch (e: ParseException) {
            throw InvalidTokenException("malformed token")
        }
        return toIdentity(claims)
    }

    private fun toIdentity(claims: JWTClaimsSet): VerifiedIdentity {
        val latest = Instant.now().plusSeconds(MAX_CLOCK_SKEW_SECONDS.toLong())
        val subject = claims.subject
        if (subject.isNullOrBlank() || subject.length > MAX_SUBJECT_LENGTH) throw InvalidTokenException("invalid subject")
        if (claims.issueTime.toInstant().isAfter(latest)) throw InvalidTokenException("issued in the future")
        val authTime = try {
            claims.getLongClaim("auth_time")?.let(Instant::ofEpochSecond)
        } catch (e: ParseException) {
            null
        } ?: throw InvalidTokenException("invalid auth_time")
        if (authTime.isAfter(latest)) throw InvalidTokenException("auth_time in the future")

        return VerifiedIdentity(
            provider = PROVIDER,
            subject = subject,
            email = stringClaim(claims, "email"),
            emailVerified = runCatching { claims.getBooleanClaim("email_verified") }.getOrNull() == true,
            name = stringClaim(claims, "name"),
            authTime = authTime,
            signInMethod = runCatching { claims.getJSONObjectClaim("firebase")?.get("sign_in_provider") as? String }
                .getOrNull(),
        )
    }

    private fun stringClaim(claims: JWTClaimsSet, name: String): String? =
        runCatching { claims.getStringClaim(name) }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val PROVIDER = "firebase"
        const val FIREBASE_JWKS_URL =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
        private const val MAX_CLOCK_SKEW_SECONDS = 60
        private const val MAX_TOKEN_LENGTH = 8192
        private const val MAX_SUBJECT_LENGTH = 128

        fun issuerFor(projectId: String) = "https://securetoken.google.com/$projectId"

        /** Production: RS256 tokens signed by Google's published keys (fetched and cached). */
        fun firebase(projectId: String): IdTokenVerifier =
            withKeys(
                projectId,
                JWKSourceBuilder.create<SecurityContext>(URI(FIREBASE_JWKS_URL).toURL()).retrying(true).build(),
            )

        /** RS256 tokens checked against [keys]; used by [firebase] and by tests with a test key. */
        fun withKeys(projectId: String, keys: JWKSource<SecurityContext>): IdTokenVerifier =
            IdTokenVerifier(projectId, JWSVerificationKeySelector(JWSAlgorithm.RS256, keys))

        /** Development only (ED-29): HS256 tokens minted locally by `seebrook dev-token`. */
        fun development(projectId: String, secret: ByteArray): IdTokenVerifier {
            require(secret.size >= 32) { "development auth secret must be at least 32 bytes" }
            return IdTokenVerifier(
                projectId,
                SingleKeyJWSKeySelector(JWSAlgorithm.HS256, SecretKeySpec(secret, "HmacSHA256")),
            )
        }
    }
}
