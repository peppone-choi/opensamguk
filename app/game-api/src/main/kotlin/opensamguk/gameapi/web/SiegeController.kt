package opensamguk.gameapi.web

import opensamguk.gameapi.read.CampForbidden
import opensamguk.gameapi.read.SiegeReader
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 공성 조회(읽기 전용). 인증 규칙은 [CampController] 와 같다: 401(principal 없음) · 403(남의 장수) · 200 + status. */
@RestController
class SiegeController(private val reader: SiegeReader) {
    @GetMapping("/api/sieges")
    fun sieges(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reader.sieges(generalId, userId))
        } catch (_: CampForbidden) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }
}
