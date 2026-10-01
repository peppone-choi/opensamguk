package opensamguk.gameapi.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import java.nio.charset.StandardCharsets

/** Authentication failure on an existing protected route; authorization failures keep their 403. */
class AuthRequiredAuthenticationEntryPoint : AuthenticationEntryPoint {
    override fun commence(request: HttpServletRequest, response: HttpServletResponse,
        authException: AuthenticationException) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        // Fixed public message: neither JWT contents nor exception details cross this boundary.
        response.writer.write("""{"error":{"code":"AUTH_REQUIRED","message":"로그인이 필요합니다."}}""")
    }
}
