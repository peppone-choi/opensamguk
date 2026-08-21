package opensamguk.boardapi.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import opensamguk.infra.read.UserRepository
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class BoardJwtAuthenticationFilter(
    private val verifier: BoardApiJwtVerifier,
    private val userRepository: UserRepository,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = resolveToken(request)?.let(verifier::verifyAccessToken)
        val user = principal?.userId?.let(userRepository::findById)?.orElse(null)
        if (user != null && SecurityContextHolder.getContext().authentication == null) {
            val details = BoardUserDetails(user)
            val authentication = UsernamePasswordAuthenticationToken(details, null, details.authorities)
            authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
            SecurityContextHolder.getContext().authentication = authentication
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val bearer = request.getHeader("Authorization") ?: return null
        return bearer.takeIf { it.startsWith("Bearer ") }?.substring(7)
    }
}
