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

    private fun error(status: Int, code: String): ResponseEntity<Any> = ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore()).body(mapOf("error" to mapOf("code" to code)))
}
