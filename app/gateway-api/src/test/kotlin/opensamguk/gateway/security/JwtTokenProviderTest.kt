package opensamguk.gateway.security

import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JwtTokenProviderTest {

    private val secret = java.util.Base64.getEncoder().encodeToString(Keys.secretKeyFor(io.jsonwebtoken.SignatureAlgorithm.HS256).encoded)
    private val provider = JwtTokenProvider(secret, 1000L, 2000L)

    @Test
    fun `generate and validate access token`() {
        val token = provider.generateAccessToken(1L, "testuser", "USER")
        assertTrue(provider.validateToken(token))
        assertEquals(1L, provider.getUserIdFromToken(token))
        assertEquals("testuser", provider.getUsernameFromToken(token))
        assertEquals("USER", provider.getRoleFromToken(token))
    }

    @Test
    fun `generate and validate refresh token`() {
        val token = provider.generateRefreshToken(1L)
        assertTrue(provider.validateToken(token))
        assertEquals(1L, provider.getUserIdFromToken(token))
    }

    @Test
    fun `invalid token returns false`() {
        assertFalse(provider.validateToken("invalid.token.here"))
    }

    @Test
    fun `expired token returns false`() {
        val shortProvider = JwtTokenProvider(secret, 1L, 1L)
        val token = shortProvider.generateAccessToken(1L, "testuser", "USER")
        Thread.sleep(10)
        assertFalse(shortProvider.validateToken(token))
    }
}
