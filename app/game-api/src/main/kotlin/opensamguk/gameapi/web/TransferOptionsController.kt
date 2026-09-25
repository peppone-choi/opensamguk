package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.TransferOptionsService
import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.logic.input.TransferInput
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class TransferOptionsController(private val service: TransferOptionsService) {
    @GetMapping("/api/commands/gift-options")
    fun gift(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        options(TransferInput.GIFT, generalId, userId)

    @GetMapping("/api/commands/donate-options")
    fun donate(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        options(TransferInput.DONATE, generalId, userId)

    private fun options(inputId: String, generalId: Int, userId: Long?): ResponseEntity<Any> {
        if (userId == null || userId <= 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.options(inputId, generalId, userId)) }
        catch (_: DomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
