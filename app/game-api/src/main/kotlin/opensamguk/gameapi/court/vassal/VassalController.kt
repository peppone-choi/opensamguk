package opensamguk.gameapi.court.vassal

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class VassalController(private val query: VassalHttpQuery) {
    @GetMapping("/api/court/vassals")
    fun read(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> {
        if (userId == null) return error(401, "AUTH_REQUIRED", "로그인이 필요합니다.")
        return try {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.read(generalId, userId))
        } catch (_: VassalTermsForbidden) {
            error(403, "FORBIDDEN", "본인 장수로만 조회할 수 있습니다.")
        }
    }

    private fun error(status: Int, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(VassalErrorDto(VassalErrorDetailDto(code, message)))
}
