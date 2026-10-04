package opensamguk.gameapi.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets

/** JWT 검증 뒤 실행하며 controller/명령/세계 읽기 전에 publication을 확인한다. */
class ServerAdmissionFilter(private val policy: ServerAdmissionPolicy) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        // C4 실제 소스 ACK: 이 read-only GET의 기존 requireAdmin을 그대로 소비한다.
        if (request.method == "GET" && path == "/api/admin/reset-current") return true
        return !(path == "/api" || path.startsWith("/api/") || path == "/sse" || path.startsWith("/sse/"))
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        when (val decision = policy.checkHttp(JwtVerifyFilter.principal(request) != null)) {
            is ServerAdmissionDecision.Allowed -> {
                request.setAttribute(PROOF_ATTRIBUTE, decision)
                chain.doFilter(request, response)
            }
            is ServerAdmissionDecision.Denied -> {
                response.status = decision.httpStatus
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.characterEncoding = StandardCharsets.UTF_8.name()
                response.setHeader("Cache-Control", "no-store")
                val message = when (decision) {
                    ServerAdmissionDecision.Denied.AUTH_REQUIRED -> "로그인이 필요합니다."
                    ServerAdmissionDecision.Denied.NOT_PUBLIC -> "현재 공개되지 않은 서버입니다."
                    ServerAdmissionDecision.Denied.UNAVAILABLE -> "서버 공개 상태를 확인할 수 없습니다."
                }
                response.writer.write("""{"error":{"code":"${decision.code}","message":"$message"}}""")
            }
        }
    }

    companion object {
        private const val PROOF_ATTRIBUTE = "opensamguk.server.admission.proof"
        fun proof(request: HttpServletRequest): ServerAdmissionDecision.Allowed? =
            request.getAttribute(PROOF_ATTRIBUTE) as? ServerAdmissionDecision.Allowed
    }
}
