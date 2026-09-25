package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.PoliticalOptionsService
import opensamguk.gameapi.read.DomesticForbidden
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class PoliticalOptionsController(private val service: PoliticalOptionsService) {
    @GetMapping("/api/commands/political-options")
    fun options(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> {
        if (userId == null || userId <= 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.options(generalId, userId)) }
        catch (_: DomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }

    @GetMapping("/api/commands/political-consent-options")
    fun consentOptions(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> {
        if (userId == null || userId <= 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.consentOptions(generalId, userId)) }
        catch (_: DomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
