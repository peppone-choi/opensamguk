package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.PersonalOptionsService
import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.logic.input.PersonalInput
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class PersonalOptionsController(private val service: PersonalOptionsService) {
    @GetMapping("/api/commands/travel-options")
    fun travel(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(PersonalInput.TRAVEL, generalId, userId)

    @GetMapping("/api/commands/self-train-options")
    fun selfTrain(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(PersonalInput.SELF_TRAIN, generalId, userId)

    @GetMapping("/api/commands/recuperate-options")
    fun recuperate(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(PersonalInput.RECUPERATE, generalId, userId)

    private fun options(inputId: String, generalId: Int, userId: Long?): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.options(inputId, generalId, userId)) }
        catch (_: DomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
