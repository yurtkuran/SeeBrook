package cc.seebrook.platform.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.HexFormat

/**
 * Mints Firebase-shaped ID tokens for local development (ED-29), so the API can be exercised
 * without a Firebase project. Only accepted by a server running in development auth mode.
 */
object DevTokens {
    /** A stable fake Firebase UID for [email], so the same email always signs in as the same account. */
    fun subjectFor(email: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(email.trim().lowercase().toByteArray())
        return "dev-" + HexFormat.of().formatHex(digest).take(24)
    }

    fun mint(
        projectId: String,
        secret: ByteArray,
        email: String,
        emailVerified: Boolean = true,
        validFor: Duration = Duration.ofHours(1),
        now: Instant = Instant.now(),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(IdTokenVerifier.issuerFor(projectId))
            .audience(projectId)
            .subject(subjectFor(email))
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(validFor)))
            .claim("auth_time", now.epochSecond)
            .claim("email", email.trim())
            .claim("email_verified", emailVerified)
            .claim("firebase", mapOf("sign_in_provider" to "password"))
            .build()
        return SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims).apply { sign(MACSigner(secret)) }.serialize()
    }
}
