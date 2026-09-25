package opensamguk.gameapi.web

import opensamguk.gameapi.read.HwihaCampForbidden
import opensamguk.gameapi.read.RoadFortReader
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class RoadFortController(private val reader: RoadFortReader) {
    @GetMapping("/api/hwiha/road-forts")
    fun forts(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reader.forts(generalId, userId))
        } catch (_: HwihaCampForbidden) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }
}
