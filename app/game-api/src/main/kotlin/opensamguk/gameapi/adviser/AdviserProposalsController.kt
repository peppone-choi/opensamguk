package opensamguk.gameapi.adviser

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class AdviserProposalsController(private val query: AdviserProposalsQuery) {
    @GetMapping("/api/retinue/proposals")
    fun read(@AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: String?): ResponseEntity<Any> {
        if (userId == null) return error(401, "AUTH_REQUIRED", "로그인이 필요합니다.")
        // D124 GET 공통 경계(K8 · K3): 없음 · 빈 값 · 10진 정수 아님 · Int 범위 밖 · 0 이하 → 400. 인증이 먼저다.
        val actorId = generalId?.takeIf { DECIMAL.matches(it) }?.toIntOrNull()?.takeIf { it > 0 }
            ?: return error(400, "INVALID_GENERAL_ID", "장수 번호가 올바르지 않습니다.")
        return try {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.read(actorId, userId))
        } catch (_: AdviserProposalsForbidden) {
            error(403, "FORBIDDEN", "본인 장수로만 조회할 수 있습니다.")
        }
    }

    private companion object {
        val DECIMAL = Regex("[0-9]+")
    }

    private fun error(status: Int, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(AdviserProposalsErrorDto(AdviserProposalsErrorDetailDto(code, message)))
}
