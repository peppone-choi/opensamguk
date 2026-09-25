package opensamguk.gameapi.web

import opensamguk.gameapi.read.HwihaVisionForbidden
import opensamguk.gameapi.read.HwihaVisionReader
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 휘하 시야 조회(읽기 전용) — [HwihaCampController] 와 같은 관문: principal 없음·범위 밖이면 401,
 * `?generalId=` 가 본인 장수가 아니면 403, 그 밖의 상태는 200 + `status`. 응답은 보는 사람마다 다르므로 no-store.
 */
@RestController
class HwihaVisionController(private val reader: HwihaVisionReader) {
    @GetMapping("/api/hwiha/visibility")
    fun visibility(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        guarded(userId) { reader.visibility(generalId, it) }

    @GetMapping("/api/hwiha/corps")
    fun corps(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        guarded(userId) { reader.corps(generalId, it) }

    @GetMapping("/api/hwiha/scout-options")
    fun scoutOptions(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        guarded(userId) { reader.scoutOptions(generalId, it) }

    private fun guarded(userId: Long?, read: (Long) -> Any): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read(userId))
        } catch (_: HwihaVisionForbidden) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }
}
