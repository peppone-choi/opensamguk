package opensamguk.gameapi.court.reward

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class RewardOptionsController(private val query: RewardOptionsQuery) {
    @GetMapping("/api/court/reward-options")
    fun options(@AuthenticationPrincipal userId: Long?, @RequestParam(required = false) generalId: String?,
        @RequestParam(required = false) retainerId: String?, @RequestParam(required = false) money: String?,
        response: HttpServletResponse): ResponseEntity<Any> {
        response.setHeader("Cache-Control", "no-store")
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE) return error(401, "AUTH_REQUIRED")
        val actor = positiveId(generalId) ?: return error(400, "INVALID_GENERAL_ID")
        val card = retainerId?.let { positiveId(it) ?: return error(400, "INVALID_RETAINER_ID") }
        if (money != null && card == null) return error(400, "PREVIEW_TARGET_REQUIRED")
        return try {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.options(actor, userId, card, money))
        } catch (_: RewardOptionsForbidden) { error(403, "FORBIDDEN") }
    }

    private fun positiveId(raw: String?): Int? = raw?.takeIf { Regex("[1-9][0-9]*").matches(it) }
        ?.toIntOrNull()?.takeIf { it > 0 }

    private fun error(status: Int, code: String): ResponseEntity<Any> {
        val message = when (code) {
            "AUTH_REQUIRED" -> "로그인이 필요합니다."
            "INVALID_GENERAL_ID" -> "장수 번호를 확인해 주세요."
            "INVALID_RETAINER_ID" -> "인물 카드 번호를 확인해 주세요."
            "PREVIEW_TARGET_REQUIRED" -> "상사 금을 조회할 인물 카드를 선택해 주세요."
            else -> "본인 장수로만 조회할 수 있습니다."
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(mapOf("error" to mapOf("code" to code, "message" to message)))
    }
}
