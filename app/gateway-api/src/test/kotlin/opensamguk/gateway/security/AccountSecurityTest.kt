package opensamguk.gateway.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.web.util.matcher.AntPathRequestMatcher

class AccountSecurityTest {
    @Test
    fun `account endpoints are not public`() {
        val request = org.springframework.mock.web.MockHttpServletRequest("POST", "/auth/account/password").apply {
            servletPath = "/auth/account/password"
        }
        assertTrue(AntPathRequestMatcher("/auth/account/**").matches(request))
        assertFalse(AntPathRequestMatcher("/auth/register").matches(request))
        assertFalse(AntPathRequestMatcher("/auth/login").matches(request))
        assertFalse(AntPathRequestMatcher("/auth/refresh").matches(request))
    }
}
