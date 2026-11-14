package cc.seebrook.app.support

import cc.seebrook.platform.auth.IdTokenVerifier
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Firebase-shaped RS256 tokens signed by a test key, verified by the production verifier code
 * path (ED-28) — tests need no Firebase project or network.
 */
object TestAuth {
    const val PROJECT_ID = "seebrook-test"
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()

    val verifier: IdTokenVerifier =
        IdTokenVerifier.withKeys(PROJECT_ID, ImmutableJWKSet<SecurityContext>(JWKSet(key.toPublicJWK())))

    fun token(uid: String, email: String?, emailVerified: Boolean = true, signInMethod: String = "password"): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(IdTokenVerifier.issuerFor(PROJECT_ID))
            .audience(PROJECT_ID)
            .subject(uid)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
            .claim("auth_time", now.epochSecond)
            .apply { if (email != null) claim("email", email) }
            .claim("email_verified", emailVerified)
            .claim("firebase", mapOf("sign_in_provider" to signInMethod))
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
            .apply { sign(RSASSASigner(key)) }.serialize()
    }

    fun identity(uid: String, email: String?, emailVerified: Boolean = true) =
        verifier.verify(token(uid, email, emailVerified))
}
