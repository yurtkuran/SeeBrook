package cc.seebrook.platform.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Date

class IdTokenVerifierTest {
    private val projectId = "seebrook-test"
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val verifier = IdTokenVerifier.withKeys(projectId, ImmutableJWKSet<SecurityContext>(JWKSet(key.toPublicJWK())))

    private fun claims(
        now: Instant = Instant.now(),
        issuer: String = IdTokenVerifier.issuerFor(projectId),
        audience: String = projectId,
        subject: String? = "firebase-uid-1",
        expires: Instant = now.plus(Duration.ofHours(1)),
        authTime: Instant? = now,
        emailVerified: Boolean = true,
    ): JWTClaimsSet = JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(audience)
        .subject(subject)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(expires))
        .apply { if (authTime != null) claim("auth_time", authTime.epochSecond) }
        .claim("email", "Owner@Example.test")
        .claim("email_verified", emailVerified)
        .claim("firebase", mapOf("sign_in_provider" to "apple.com"))
        .build()

    private fun sign(claims: JWTClaimsSet, signingKey: RSAKey = key): String =
        SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(), claims)
            .apply { sign(RSASSASigner(signingKey)) }.serialize()

    @Test
    fun `accepts a valid token and exposes its identity`() {
        val now = Instant.now()
        val identity = verifier.verify(sign(claims(now = now)))

        identity.provider shouldBe "firebase"
        identity.subject shouldBe "firebase-uid-1"
        identity.email shouldBe "Owner@Example.test"
        identity.emailVerified shouldBe true
        identity.authTime shouldBe Instant.ofEpochSecond(now.epochSecond)
        identity.signInMethod shouldBe "apple.com"
    }

    @Test
    fun `reports an unverified email`() {
        verifier.verify(sign(claims(emailVerified = false))).emailVerified shouldBe false
    }

    @Test
    fun `rejects a token for another project`() {
        shouldThrow<InvalidTokenException> { verifier.verify(sign(claims(audience = "other-project"))) }
        shouldThrow<InvalidTokenException> {
            verifier.verify(sign(claims(issuer = IdTokenVerifier.issuerFor("other-project"))))
        }
    }

    @Test
    fun `rejects an expired token`() {
        val past = Instant.now().minus(Duration.ofHours(3))
        shouldThrow<InvalidTokenException> { verifier.verify(sign(claims(now = past, expires = past.plusSeconds(3600)))) }
    }

    @Test
    fun `rejects a token signed by an unknown key`() {
        val attacker = RSAKeyGenerator(2048).keyID("test-key").generate()
        shouldThrow<InvalidTokenException> { verifier.verify(sign(claims(), attacker)) }
    }

    @Test
    fun `rejects a tampered token`() {
        val parts = sign(claims()).split(".")
        val forged = claims(subject = "someone-else").toString().toByteArray()
        shouldThrow<InvalidTokenException> {
            verifier.verify(listOf(parts[0], Base64URL.encode(forged).toString(), parts[2]).joinToString("."))
        }
    }

    @Test
    fun `rejects unsigned and HMAC-signed tokens`() {
        shouldThrow<InvalidTokenException> { verifier.verify(PlainJWT(claims()).serialize()) }

        // Algorithm confusion: an HS256 token keyed with the public key must not pass as RS256.
        val hmac = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims())
            .apply { sign(MACSigner(key.toPublicJWK().toRSAPublicKey().encoded.copyOf(64))) }.serialize()
        shouldThrow<InvalidTokenException> { verifier.verify(hmac) }
    }

    @Test
    fun `rejects missing subject or sign-in time`() {
        shouldThrow<InvalidTokenException> { verifier.verify(sign(claims(subject = null))) }
        shouldThrow<InvalidTokenException> { verifier.verify(sign(claims(authTime = null))) }
    }

    @Test
    fun `rejects a sign-in time in the future`() {
        shouldThrow<InvalidTokenException> {
            verifier.verify(sign(claims(authTime = Instant.now().plus(Duration.ofHours(1)))))
        }
    }

    @Test
    fun `rejects garbage`() {
        shouldThrow<InvalidTokenException> { verifier.verify("not-a-token") }
        shouldThrow<InvalidTokenException> { verifier.verify("a".repeat(10_000)) }
    }

    @Test
    fun `development tokens verify only with the development key`() {
        val secret = "s".repeat(40).toByteArray()
        val dev = IdTokenVerifier.development("seebrook-dev", secret)
        val token = DevTokens.mint("seebrook-dev", secret, "owner01@example.test")

        dev.verify(token).subject shouldBe DevTokens.subjectFor("owner01@example.test")
        shouldThrow<InvalidTokenException> { verifier.verify(token) }
        shouldThrow<InvalidTokenException> {
            IdTokenVerifier.development("seebrook-dev", "t".repeat(40).toByteArray()).verify(token)
        }
    }
}
