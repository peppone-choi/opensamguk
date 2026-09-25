package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.HwihaLegacyCourtOptionsService
import opensamguk.gameapi.read.HwihaDomesticForbidden
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class HwihaLegacyCourtOptionsController(private val service: HwihaLegacyCourtOptionsService) {
    @GetMapping("/api/commands/legacy-court-options")
    fun options(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int,
        @RequestParam inputId: String): ResponseEntity<Any> {
        if (userId == null || userId <= 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.options(generalId, userId, inputId)) }
        catch (_: HwihaDomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
