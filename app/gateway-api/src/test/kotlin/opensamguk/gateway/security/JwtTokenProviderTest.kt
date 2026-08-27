package opensamguk.gateway.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Encoders
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.common.auth.GatewayJwtKeys
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.info.Info
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JwtTokenProviderTest {
    private val now = Instant.parse("2026-08-21T00:00:00Z")
    private val keys = rsaKeyPair()

    @Test
    fun `RS256 access and refresh tokens enforce their contracts`() {
        val provider = provider(rsaProperties(), now)
        val access = provider.generateAccessToken(1L, "USER")
        val refresh = provider.generateRefreshToken(1L)

        assertTrue(provider.validateAccessToken(access))
        assertFalse(provider.validateRefreshToken(access))
        assertTrue(provider.validateRefreshToken(refresh))
        assertFalse(provider.validateAccessToken(refresh))
        assertEquals(1L, provider.getUserIdFromToken(access))
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
        val access = provider.generateAccessToken(1L, "USER")
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
    fun `access tokens never carry display claims`() {
        val provider = provider(rsaProperties(), now)
        val access = provider.generateAccessToken(1L, "USER")

        assertTrue(provider.validateAccessToken(access))
        assertEquals(1L, provider.getUserIdFromToken(access))

        // OPENSAM-220/#483: 표시 클레임이 다시 새지 않도록 sub/iat/exp/token_type/role/iss/aud 전량을 잠근다.
        // 토큰은 고정 시각 now 기준으로 발급됐다. 파서에도 같은 시계를 줘야 실제 벽시계에 따라
        // ExpiredJwtException 으로 깨지지 않는다.
        val claims = Jwts.parser()
            .clock { Date.from(now) }
            .verifyWith(keys.public)
            .build()
            .parseSignedClaims(access)
            .payload
        assertEquals(
            setOf("sub", "iat", "exp", GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ROLE, "iss", "aud"),
            claims.keys,
        )
        assertEquals("USER", claims[GatewayJwtClaims.ROLE])
    }

    @Test
    fun `expired token is rejected without wall clock sleeps`() {
        val access = provider(rsaProperties(), now).generateAccessToken(1L, "USER")

        assertFalse(provider(rsaProperties(), now.plusSeconds(61)).validateAccessToken(access))
    }

    @Test
    fun `actuator info exposes the RSA public key fingerprint for cross-service comparison`() {
        val provider = provider(rsaProperties(), now)
        val info = Info.Builder()

        provider.contribute(info)

        val jwt = info.build().details["jwt"] as Map<*, *>
        assertEquals("rsa-audience-v1", jwt["verifier"])
        assertEquals(GatewayJwtKeys.rsaPublicKeyFingerprint(keys.public), jwt["publicKeySha256"])
        assertEquals("RS256", jwt["signingMode"])
        assertEquals(false, jwt["legacyAccessAccepted"])
        assertEquals(false, jwt["legacyRefreshAccepted"])
    }

    @Test
    fun `actuator info does not fail startup when no RSA key pair is configured`() {
        val provider = provider(legacyProperties(), now)
        val info = Info.Builder()

        provider.contribute(info)

        val jwt = info.build().details["jwt"] as Map<*, *>
        assertEquals("unconfigured", jwt["publicKeySha256"])
        assertEquals("LEGACY_HS256", jwt["signingMode"])
        assertEquals(true, jwt["legacyAccessAccepted"])
        assertEquals(true, jwt["legacyRefreshAccepted"])
    }

    @Test
    fun `RS256 with retained legacy fallback reports only current safe rollout state`() {
        // OPENSAM-220 팔로업: 2026-08-23 프로덕션 형상 — signingMode=RS256, JWT_LEGACY_SECRET/accept-until
        // 은 아직 지우지 않았다(전환창 종료 전). 이 조합에서 기동과 InfoContributor 출력이 둘 다 안전한지 고정한다.
        val provider = provider(rsaProperties().apply { includeLegacyFallback() }, now)
        val info = Info.Builder()

        provider.contribute(info)

        val jwt = info.build().details["jwt"] as Map<*, *>
        assertEquals(
            setOf(
                "verifier",
                "publicKeySha256",
                "signingMode",
                "legacyAccessAccepted",
                "legacyRefreshAccepted",
            ),
            jwt.keys,
        )
        assertEquals("RS256", jwt["signingMode"])
        assertEquals(true, jwt["legacyAccessAccepted"])
        assertEquals(true, jwt["legacyRefreshAccepted"])
    }

    @Test
    fun `actuator legacy acceptance reflects the injected clock at each cutoff`() {
        val accessCutoffProvider = provider(
            rsaProperties().apply { includeLegacyFallback() },
            Instant.parse("2026-08-28T00:15:00Z"),
        )
        val accessCutoffInfo = Info.Builder()

        accessCutoffProvider.contribute(accessCutoffInfo)

        val accessCutoffJwt = accessCutoffInfo.build().details["jwt"] as Map<*, *>
        assertEquals(false, accessCutoffJwt["legacyAccessAccepted"])
        assertEquals(true, accessCutoffJwt["legacyRefreshAccepted"])

        val refreshCutoffProvider = provider(
            rsaProperties().apply { includeLegacyFallback() },
            Instant.parse("2026-09-04T00:00:00Z"),
        )
        val refreshCutoffInfo = Info.Builder()

        refreshCutoffProvider.contribute(refreshCutoffInfo)

        val refreshCutoffJwt = refreshCutoffInfo.build().details["jwt"] as Map<*, *>
        assertEquals(false, refreshCutoffJwt["legacyAccessAccepted"])
        assertEquals(false, refreshCutoffJwt["legacyRefreshAccepted"])
    }

    private fun provider(properties: GatewayJwtProperties, instant: Instant): JwtTokenProvider =
        JwtTokenProvider(properties, Clock.fixed(instant, ZoneOffset.UTC))

    private fun GatewayJwtProperties.includeLegacyFallback() {
        legacySecret = Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().encoded)
        legacyAccessAcceptUntil = "2026-08-28T00:15:00Z"
        legacyRefreshAcceptUntil = "2026-09-04T00:00:00Z"
    }

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
