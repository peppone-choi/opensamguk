package opensamguk.common.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Encoders
import io.jsonwebtoken.security.Keys
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class GatewayAccessTokenVerifierTest {
    private val now = Instant.parse("2026-08-21T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val keys = rsaKeyPair()
    private val publicKey = Encoders.BASE64.encode(keys.public.encoded)

    @Test
    fun `RS256 access token requires the fixed issuer and consumer audience`() {
        val verifier = GatewayAccessTokenVerifier(publicKey, GatewayJwtContract.GAME_API_AUDIENCE, clock = clock)

        assertEquals(
            GatewayPrincipal(42L, "USER"),
            verifier.verifyAccessToken(rsaToken()),
        )
        assertNull(verifier.verifyAccessToken(rsaToken(issuer = "forged-issuer")))
        assertNull(verifier.verifyAccessToken(rsaToken(audiences = setOf(GatewayJwtContract.BOARD_API_AUDIENCE))))
        assertNull(verifier.verifyAccessToken(rsaToken(tokenType = GatewayJwtClaims.REFRESH_TOKEN)))
    }

    @Test
    fun `consumer public key cannot validate an attacker signed token`() {
        val verifier = GatewayAccessTokenVerifier(publicKey, GatewayJwtContract.GAME_API_AUDIENCE, clock = clock)

        assertNull(verifier.verifyAccessToken(rsaToken(signingKeys = rsaKeyPair())))
        val algorithmConfusionToken = Jwts.builder()
            .subject("42")
            .issuer(GatewayJwtContract.ISSUER)
            .audience().add(GatewayJwtContract.GAME_API_AUDIENCE).and()
            .expiration(Date.from(now.plusSeconds(60)))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN)
            .claim(GatewayJwtClaims.ROLE, "ADMIN")
            .signWith(Keys.hmacShaKeyFor(keys.public.encoded), Jwts.SIG.HS256)
            .compact()
        assertNull(verifier.verifyAccessToken(algorithmConfusionToken))
    }

    @Test
    fun `public key fingerprint is stable and distinguishes rollout identities`() {
        val verifier = GatewayAccessTokenVerifier(publicKey, GatewayJwtContract.GAME_API_AUDIENCE, clock = clock)
        val sameKey = GatewayAccessTokenVerifier(publicKey, GatewayJwtContract.BOARD_API_AUDIENCE, clock = clock)
        val otherKey = GatewayAccessTokenVerifier(
            Encoders.BASE64.encode(rsaKeyPair().public.encoded),
            GatewayJwtContract.GAME_API_AUDIENCE,
            clock = clock,
        )

        assertEquals(verifier.publicKeyFingerprint, sameKey.publicKeyFingerprint)
        assertNotEquals(verifier.publicKeyFingerprint, otherKey.publicKeyFingerprint)
        assertEquals(64, requireNotNull(verifier.publicKeyFingerprint).length)
    }

    @Test
    fun `consumer rejects a signed token without expiration`() {
        val token = Jwts.builder()
            .subject("42")
            .issuer(GatewayJwtContract.ISSUER)
            .audience().add(GatewayJwtContract.GAME_API_AUDIENCE).and()
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN)
            .claim(GatewayJwtClaims.ROLE, "USER")
            .signWith(keys.private, Jwts.SIG.RS256)
            .compact()

        assertNull(GatewayAccessTokenVerifier(publicKey, GatewayJwtContract.GAME_API_AUDIENCE, clock = clock)
            .verifyAccessToken(token))
    }

    @Test
    fun `legacy HS256 acceptance is bounded by cutoff and token expiration`() {
        val secret = Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().encoded)
        val cutoff = now.plusSeconds(900)
        val verifier = GatewayAccessTokenVerifier(
            publicKey,
            GatewayJwtContract.GAME_API_AUDIENCE,
            legacySecret = secret,
            legacyAcceptUntil = cutoff,
            clock = clock,
        )

        assertEquals(GatewayPrincipal(42L, "USER"), verifier.verifyAccessToken(legacyToken(secret, cutoff)))
        assertNull(verifier.verifyAccessToken(legacyToken(secret, cutoff.plusSeconds(1))))
        assertNull(
            GatewayAccessTokenVerifier(
                publicKey,
                GatewayJwtContract.GAME_API_AUDIENCE,
                legacySecret = secret,
                legacyAcceptUntil = cutoff,
                clock = Clock.fixed(cutoff, ZoneOffset.UTC),
            ).verifyAccessToken(legacyToken(secret, cutoff)),
        )
    }

    @Test
    fun `legacy configuration fails closed unless secret and cutoff are paired`() {
        val secret = Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().encoded)

        assertFailsWith<IllegalArgumentException> {
            GatewayAccessTokenVerifier(publicKey, GatewayJwtContract.GAME_API_AUDIENCE, legacySecret = secret)
        }
        assertFailsWith<IllegalArgumentException> {
            GatewayAccessTokenVerifier(
                publicKey,
                GatewayJwtContract.GAME_API_AUDIENCE,
                legacyAcceptUntil = now.plusSeconds(60),
            )
        }
    }

    private fun rsaToken(
        signingKeys: KeyPair = keys,
        issuer: String = GatewayJwtContract.ISSUER,
        audiences: Set<String> = GatewayJwtContract.ACCESS_AUDIENCES,
        tokenType: String = GatewayJwtClaims.ACCESS_TOKEN,
    ): String {
        val builder = Jwts.builder()
            .subject("42")
            .issuer(issuer)
            .issuedAt(Date.from(now.minusSeconds(1)))
            .expiration(Date.from(now.plusSeconds(300)))
            .claim(GatewayJwtClaims.TOKEN_TYPE, tokenType)
            .claim(GatewayJwtClaims.ROLE, "USER")
        audiences.forEach { builder.audience().add(it).and() }
        return builder.signWith(signingKeys.private, Jwts.SIG.RS256).compact()
    }

    private fun legacyToken(secret: String, expiration: Instant): String =
        Jwts.builder()
            .subject("42")
            .issuedAt(Date.from(now.minusSeconds(1)))
            .expiration(Date.from(expiration))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN)
            .claim(GatewayJwtClaims.ROLE, "USER")
            .signWith(Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(secret)))
            .compact()

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}
