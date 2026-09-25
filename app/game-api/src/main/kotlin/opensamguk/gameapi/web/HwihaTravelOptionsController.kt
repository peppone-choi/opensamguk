package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.HwihaTravelPrecheckService
import opensamguk.gameapi.precheck.TravelReadForbidden
import opensamguk.logic.input.TravelInput
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class HwihaTravelOptionsController(private val precheck: HwihaTravelPrecheckService) {
    @GetMapping("/api/commands/move-options")
    fun move(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        options(TravelInput.MOVE, generalId, userId)

    @GetMapping("/api/commands/forced-march-options")
    fun forcedMarch(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        options(TravelInput.FORCED_MARCH, generalId, userId)

    @GetMapping("/api/commands/return-options")
    fun returnHome(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        options(TravelInput.RETURN, generalId, userId)

    private fun options(inputId: String, generalId: Int, userId: Long?): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok(precheck.options(generalId, inputId, userId)) }
        catch (_: TravelReadForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
