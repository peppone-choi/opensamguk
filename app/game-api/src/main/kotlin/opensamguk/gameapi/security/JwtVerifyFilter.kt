package opensamguk.gameapi.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import opensamguk.common.auth.GatewayPrincipal
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * F2 Wave 1 — verify the `Authorization: Bearer <sam_access>` JWT on every request and, when valid,
 * populate the [SecurityContextHolder] with the verified principal.
 *
 * Mirrors gateway-api's [opensamguk.gateway.security.JwtAuthenticationFilter] resolve flow, but is
 * VERIFY-ONLY and STATELESS: game-api has no user store / [org.springframework.security.core.userdetails.UserDetailsService],
 * so the principal is simply the verified user id (Long). Controllers read it via
 * `@AuthenticationPrincipal userId: Long?` (null when the request is anonymous).
 *
 * A missing/invalid token is NOT rejected here — the filter just leaves the context anonymous and lets
 * [GameApiSecurityConfig] decide which routes require authentication (public reads stay open).
 */
@Component
class JwtVerifyFilter(
    private val verifier: GameApiJwtVerifier,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = resolveToken(request)
        val principal = token?.let(verifier::verifyAccessToken)
        if (principal != null && SecurityContextHolder.getContext().authentication == null) {
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal)
            val auth = UsernamePasswordAuthenticationToken(
                principal.userId,
                null,
                listOf(SimpleGrantedAuthority("ROLE_${principal.role}")),
            )
            auth.details = WebAuthenticationDetailsSource().buildDetails(request)
            SecurityContextHolder.getContext().authentication = auth
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val bearer = request.getHeader("Authorization") ?: return null
        return if (bearer.startsWith("Bearer ")) bearer.substring(7) else null
    }

    companion object {
        const val PRINCIPAL_ATTRIBUTE = "opensamguk.gateway.principal"

        fun principal(request: HttpServletRequest): GatewayPrincipal? =
            request.getAttribute(PRINCIPAL_ATTRIBUTE) as? GatewayPrincipal
    }
}
