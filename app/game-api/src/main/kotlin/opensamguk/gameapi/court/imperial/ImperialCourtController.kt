package opensamguk.gameapi.court.imperial

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class ImperialCourtController(private val query: ImperialCourtQuery) {
    @GetMapping("/api/imperial/court")
    fun read(@AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: String?): ResponseEntity<Any> {
        if (userId == null) return error(401, "AUTH_REQUIRED", "로그인이 필요합니다.")
        val actorId = generalId?.toIntOrNull()?.takeIf { it > 0 }
            ?: return error(400, "INVALID_GENERAL_ID", "장수 번호를 확인해 주세요.")
        return try {
            val result = query.read(actorId, userId)
            val status = if (result.status == ImperialCourtStatus.STATE_UNAVAILABLE) 409 else 200
            ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(result)
        } catch (_: ImperialCourtForbidden) {
            error(403, "FORBIDDEN", "본인 장수로만 조회할 수 있습니다.")
        } catch (_: ImperialCourtUnavailable) {
            unavailable()
        }
    }

    /** Exception translation must run after the query transaction has rolled back. */
    private fun unavailable(): ResponseEntity<Any> = ResponseEntity.status(409).cacheControl(CacheControl.noStore())
        .body(ImperialCourtDto(ImperialCourtStatus.STATE_UNAVAILABLE, emptyList()))

    private fun error(status: Int, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(ImperialCourtErrorDto(ImperialCourtErrorDetailDto(code, message)))
}
