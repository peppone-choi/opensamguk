package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.HwihaPeopleOptionsService
import opensamguk.gameapi.read.HwihaDomesticForbidden
import opensamguk.logic.input.HwihaPeopleInput
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class HwihaPeopleOptionsController(private val service: HwihaPeopleOptionsService) {
    @GetMapping("/api/commands/search-options")
    fun search(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(HwihaPeopleInput.SEARCH, generalId, userId)

    @GetMapping("/api/commands/employ-options")
    fun employ(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(HwihaPeopleInput.EMPLOY, generalId, userId)

    @GetMapping("/api/commands/persuade-captive-options")
    fun persuadeCaptive(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(HwihaPeopleInput.PERSUADE_CAPTIVE, generalId, userId)

    private fun options(inputId: String, generalId: Int, userId: Long?): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.options(inputId, generalId, userId)) }
        catch (_: HwihaDomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
