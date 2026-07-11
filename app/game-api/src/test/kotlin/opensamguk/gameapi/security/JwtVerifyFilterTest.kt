package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayProfileClaims
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JwtVerifyFilterTest {
    private val secret = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    private val verifier = GameApiJwtVerifier(secret)
    private val filter = JwtVerifyFilter(verifier)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `verified access token places signed gateway profile on the request`() {
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer ${mint(GatewayJwtClaims.ACCESS_TOKEN)}")
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertEquals(42L, SecurityContextHolder.getContext().authentication?.principal)
        assertEquals("ROLE_USER", SecurityContextHolder.getContext().authentication?.authorities?.single()?.authority)
        assertEquals(
            GatewayProfileClaims(42L, "alice", "USER", "앨리스", 1, "alice.jpg", 1),
            request.getAttribute(JwtVerifyFilter.PROFILE_ATTRIBUTE),
        )
    }

    @Test
    fun `refresh token cannot populate principal or profile request attribute`() {
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer ${mint(GatewayJwtClaims.REFRESH_TOKEN)}")
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertNull(SecurityContextHolder.getContext().authentication)
        assertNull(request.getAttribute(JwtVerifyFilter.PROFILE_ATTRIBUTE))
    }

    private fun mint(tokenType: String): String {
        val key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))
        val now = Date()
        return Jwts.builder()
            .subject("42")
            .issuedAt(now)
            .expiration(Date(now.time + 60_000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, tokenType)
            .claim(GatewayJwtClaims.USERNAME, "alice")
            .claim(GatewayJwtClaims.ROLE, "USER")
            .claim(GatewayJwtClaims.NICKNAME, "앨리스")
            .claim(GatewayJwtClaims.GRADE, 1)
            .claim(GatewayJwtClaims.PICTURE, "alice.jpg")
            .claim(GatewayJwtClaims.IMAGE_SERVER, 1)
            .signWith(key)
            .compact()
    }
}
