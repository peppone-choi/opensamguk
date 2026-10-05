package opensamguk.gameapi.season

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class WorldSeasonController(private val query: WorldSeasonQuery) {
    @GetMapping("/api/world/season")
    fun read(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: String?,
    ): ResponseEntity<Any> {
        if (userId == null) return error(401, "AUTH_REQUIRED", "로그인이 필요합니다.")
        val id = generalId?.takeIf { DECIMAL.matches(it) }?.toIntOrNull()?.takeIf { it > 0 }
            ?: return error(400, "INVALID_GENERAL_ID", "장수 번호가 올바르지 않습니다.")
        return try {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.read(id, userId))
        } catch (_: WorldSeasonForbidden) {
            error(403, "FORBIDDEN", "본인 장수로만 조회할 수 있습니다.")
        }
    }

    private fun error(status: Int, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(WorldSeasonErrorDto(WorldSeasonErrorDetailDto(code, message)))

    private companion object {
        val DECIMAL = Regex("[0-9]+")
    }
}
