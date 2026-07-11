package opensamguk.gateway.security

import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayProfileClaims
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JwtTokenProviderTest {

    private val secret = java.util.Base64.getEncoder().encodeToString(Keys.secretKeyFor(io.jsonwebtoken.SignatureAlgorithm.HS256).encoded)
    private val provider = JwtTokenProvider(secret, 60_000L, 120_000L)
    private val profile = GatewayProfileClaims(
        userId = 1L,
        username = "testuser",
        role = "USER",
        nickname = "테스터",
        grade = 1,
        picture = "profile.jpg",
        imageServer = 1,
    )

    @Test
    fun `generate and validate access token`() {
        val token = provider.generateAccessToken(profile)
        assertTrue(provider.validateAccessToken(token))
        assertFalse(provider.validateRefreshToken(token))
        assertEquals(1L, provider.getUserIdFromToken(token))
        assertEquals("testuser", provider.getUsernameFromToken(token))
        assertEquals("USER", provider.getRoleFromToken(token))
        assertEquals(profile, provider.getProfileFromAccessToken(token))
    }

    @Test
    fun `generate and validate refresh token`() {
        val token = provider.generateRefreshToken(1L)
        assertTrue(provider.validateRefreshToken(token))
        assertFalse(provider.validateAccessToken(token))
        assertNull(provider.getProfileFromAccessToken(token))
        assertEquals(1L, provider.getUserIdFromToken(token))
    }

    @Test
    fun `invalid token returns false`() {
        assertFalse(provider.validateAccessToken("invalid.token.here"))
        assertFalse(provider.validateRefreshToken("invalid.token.here"))
    }

    @Test
    fun `expired token returns false`() {
        val shortProvider = JwtTokenProvider(secret, 1L, 1L)
        val token = shortProvider.generateAccessToken(profile)
        Thread.sleep(10)
        assertFalse(shortProvider.validateAccessToken(token))
    }
}
