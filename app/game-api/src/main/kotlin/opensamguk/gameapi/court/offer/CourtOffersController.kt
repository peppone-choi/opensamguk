package opensamguk.gameapi.court.offer

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class CourtOffersController(private val query: CourtOffersQuery) {
    @GetMapping("/api/court/offers")
    fun read(@AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: String?): ResponseEntity<Any> {
        if (userId == null) return error(401, "AUTH_REQUIRED", "로그인이 필요합니다.")
        val actorId = generalId?.toIntOrNull()
            ?: return error(400, "INVALID_GENERAL_ID", "장수 번호를 확인해 주세요.")
        return try {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.read(actorId, userId))
        } catch (_: CourtOffersForbidden) {
            error(403, "FORBIDDEN", "본인 장수로만 조회할 수 있습니다.")
        } catch (_: CourtOffersWorldUnavailable) {
            error(503, "WORLD_UNAVAILABLE", "현재 월드를 확인할 수 없습니다.")
        }
    }

    private fun error(status: Int, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(CourtOfferErrorDto(CourtOfferErrorDetailDto(code, message)))
}
