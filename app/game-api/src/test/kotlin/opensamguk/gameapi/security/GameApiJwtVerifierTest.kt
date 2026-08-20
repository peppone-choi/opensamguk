package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayPrincipal
import org.junit.jupiter.api.Test
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F2 Wave 1 — proves game-api verifies tokens minted with gateway-api's SHARED HS256 secret. We sign a
 * token here with the SAME default secret game-api defaults to (the gateway-api dev default), then assert
 * verify + subject(=userId) extraction. A token signed with a DIFFERENT secret must NOT verify (the
 * shared-secret trust boundary).
 */
class GameApiJwtVerifierTest {

    // identical to gateway-api's dev default + game-api application.yml default.
    private val sharedSecret = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    private val verifier = GameApiJwtVerifier(sharedSecret)

    private fun mint(
        secret: String,
        userId: Long,
        role: String? = "USER",
        tokenType: String = GatewayJwtClaims.ACCESS_TOKEN,
        expiresInMs: Long = 900_000,
        legacyProfileClaims: Boolean = false,
    ): String {
        val key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))
        val now = Date()
        val b = Jwts.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiration(Date(now.time + expiresInMs))
            .claim(GatewayJwtClaims.TOKEN_TYPE, tokenType)
            .signWith(key)
        if (role != null) b.claim(GatewayJwtClaims.ROLE, role)
        // OPENSAM-220 이전에 발급된 토큰 — 표시용 클레임이 남아 있다.
        if (legacyProfileClaims) {
            b.claim("username", "alice")
                .claim("nickname", "앨리스")
                .claim("grade", 1)
                .claim("picture", "alice.jpg")
                .claim("imgsvr", 1)
        }
        return b.compact()
    }

    @Test
    fun `verifies a token signed with the shared secret and extracts the userId subject`() {
        val token = mint(sharedSecret, userId = 42L)
        assertTrue(verifier.isValid(token))
        assertEquals(42L, verifier.getUserId(token))
        assertEquals("USER", verifier.getRole(token))
        assertEquals(GatewayPrincipal(42L, "USER"), verifier.verifyAccessToken(token))
    }

    /** OPENSAM-220 구버전 토큰 호환 — 여분 표시용 클레임은 무시되고 파싱은 깨지지 않는다. */
    @Test
    fun `accepts a legacy token that still carries profile claims and ignores them`() {
        val token = mint(sharedSecret, userId = 42L, legacyProfileClaims = true)
        assertEquals(GatewayPrincipal(42L, "USER"), verifier.verifyAccessToken(token))
    }

    @Test
    fun `rejects an unknown role`() {
        assertNull(verifier.verifyAccessToken(mint(sharedSecret, userId = 42L, role = "ROOT")))
    }

    @Test
    fun `rejects a token signed with a different secret`() {
        val other = "ZGlmZmVyZW50LXNlY3JldC1kaWZmZXJlbnQtc2VjcmV0LWRpZmZlcmVudC1zZWNyZXQ="
        val token = mint(other, userId = 42L)
        assertFalse(verifier.isValid(token))
        assertNull(verifier.getUserId(token))
    }

    @Test
    fun `rejects an expired token`() {
        val token = mint(sharedSecret, userId = 42L, expiresInMs = -1000)
        assertFalse(verifier.isValid(token))
    }

    @Test
    fun `rejects garbage`() {
        assertFalse(verifier.isValid("not-a-jwt"))
        assertNull(verifier.getUserId("not-a-jwt"))
    }

    @Test
    fun `rejects a signed refresh token and an access token missing the role claim`() {
        val refresh = mint(sharedSecret, userId = 42L, tokenType = GatewayJwtClaims.REFRESH_TOKEN)
        val incomplete = mint(sharedSecret, userId = 42L, role = null)

        assertNull(verifier.verifyAccessToken(refresh))
        assertNull(verifier.verifyAccessToken(incomplete))
    }
}
