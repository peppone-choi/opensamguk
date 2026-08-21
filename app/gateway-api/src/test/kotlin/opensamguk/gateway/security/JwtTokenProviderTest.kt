package opensamguk.gateway.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Encoders
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.common.auth.GatewayProfileClaims
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtTokenProviderTest {
    private val now = Instant.parse("2026-08-21T00:00:00Z")
    private val keys = rsaKeyPair()
    private val profile = GatewayProfileClaims(1L, "testuser", "USER", "테스터", 1, "profile.jpg", 1)

    @Test
    fun `RS256 access and refresh tokens enforce their contracts`() {
        val provider = provider(rsaProperties(), now)
        val access = provider.generateAccessToken(profile)
        val refresh = provider.generateRefreshToken(1L)

        assertTrue(provider.validateAccessToken(access))
        assertFalse(provider.validateRefreshToken(access))
        assertTrue(provider.validateRefreshToken(refresh))
        assertFalse(provider.validateAccessToken(refresh))
        assertEquals(profile, provider.getProfileFromAccessToken(access))
        assertEquals(1L, provider.getUserIdFromToken(refresh))
    }

    @Test
    fun `wrong issuer audience type and signing key are rejected`() {
        val provider = provider(rsaProperties(), now)

        assertFalse(provider.validateAccessToken(rsaToken(issuer = "other")))
        assertFalse(provider.validateAccessToken(rsaToken(audiences = setOf(GatewayJwtContract.BOARD_API_AUDIENCE))))
        assertFalse(provider.validateAccessToken(rsaToken(tokenType = GatewayJwtClaims.REFRESH_TOKEN)))
        assertFalse(provider.validateAccessToken(rsaToken(signingKeys = rsaKeyPair())))
    }

    @Test
    fun `RS256 startup rejects a mismatched key pair`() {
        val properties = rsaProperties().apply {
            publicKey = Encoders.BASE64.encode(rsaKeyPair().public.encoded)
        }

        assertFailsWith<IllegalArgumentException> { provider(properties, now) }
    }

    @Test
    fun `legacy signing requires bounded acceptance windows`() {
        val properties = legacyProperties()
        val provider = provider(properties, now)
        val access = provider.generateAccessToken(profile)
        val refresh = provider.generateRefreshToken(1L)

        assertTrue(provider.validateAccessToken(access))
        assertTrue(provider.validateRefreshToken(refresh))
        assertFalse(provider(properties, Instant.parse("2026-09-01T00:00:00Z")).validateAccessToken(access))
    }

    @Test
    fun `legacy verification configuration requires both absolute cutoffs`() {
        val missingRefresh = rsaProperties().apply {
            legacySecret = Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().encoded)
            legacyAccessAcceptUntil = "2026-08-21T00:15:00Z"
        }
        val missingAccess = rsaProperties().apply {
            legacySecret = Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().encoded)
            legacyRefreshAcceptUntil = "2026-08-28T00:00:00Z"
        }

        assertFailsWith<IllegalArgumentException> { provider(missingRefresh, now) }
        assertFailsWith<IllegalArgumentException> { provider(missingAccess, now) }
    }

    @Test
    fun `profile claims can be removed only after RS256 activation`() {
        val properties = rsaProperties().apply { includeProfileClaims = false }
        val provider = provider(properties, now)
        val access = provider.generateAccessToken(profile)

        assertTrue(provider.validateAccessToken(access))
        assertEquals(1L, provider.getUserIdFromToken(access))
        assertEquals("USER", provider.getRoleFromToken(access))
        assertNull(provider.getProfileFromAccessToken(access))
    }

    @Test
    fun `expired token is rejected without wall clock sleeps`() {
        val access = provider(rsaProperties(), now).generateAccessToken(profile)

        assertFalse(provider(rsaProperties(), now.plusSeconds(61)).validateAccessToken(access))
    }

    private fun provider(properties: GatewayJwtProperties, instant: Instant): JwtTokenProvider =
        JwtTokenProvider(properties, Clock.fixed(instant, ZoneOffset.UTC))

    private fun rsaProperties(): GatewayJwtProperties = GatewayJwtProperties().apply {
        signingMode = GatewayJwtSigningMode.RS256
        privateKey = Encoders.BASE64.encode(keys.private.encoded)
        publicKey = Encoders.BASE64.encode(keys.public.encoded)
        accessExpiration = 60_000
        refreshExpiration = 120_000
    }

    private fun legacyProperties(): GatewayJwtProperties = GatewayJwtProperties().apply {
        signingMode = GatewayJwtSigningMode.LEGACY_HS256
        legacySecret = Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().encoded)
        legacyAccessAcceptUntil = "2026-08-21T00:15:00Z"
        legacyRefreshAcceptUntil = "2026-08-28T00:00:00Z"
        accessExpiration = 60_000
        refreshExpiration = 120_000
    }

    private fun rsaToken(
        signingKeys: KeyPair = keys,
        issuer: String = GatewayJwtContract.ISSUER,
        audiences: Set<String> = GatewayJwtContract.ACCESS_AUDIENCES,
        tokenType: String = GatewayJwtClaims.ACCESS_TOKEN,
    ): String {
        val builder = Jwts.builder()
            .subject("1")
            .issuer(issuer)
            .issuedAt(Date.from(now.minusSeconds(1)))
            .expiration(Date.from(now.plusSeconds(60)))
            .claim(GatewayJwtClaims.TOKEN_TYPE, tokenType)
            .claim(GatewayJwtClaims.ROLE, "USER")
        audiences.forEach { builder.audience().add(it).and() }
        return builder.signWith(signingKeys.private, Jwts.SIG.RS256).compact()
    }

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}
