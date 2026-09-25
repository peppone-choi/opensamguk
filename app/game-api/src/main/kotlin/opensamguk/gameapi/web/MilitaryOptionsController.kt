package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.MilitaryOptionsService
import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.logic.input.MilitaryInput
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class MilitaryOptionsController(private val service: MilitaryOptionsService) {
    @GetMapping("/api/commands/conscript-options")
    fun conscript(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(MilitaryInput.CONSCRIPT, generalId, userId)
    @GetMapping("/api/commands/raise-volunteers-options")
    fun raiseVolunteers(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(MilitaryInput.RAISE_VOLUNTEERS, generalId, userId)
    @GetMapping("/api/commands/train-options")
    fun train(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(MilitaryInput.TRAIN, generalId, userId)
    @GetMapping("/api/commands/boost-morale-options")
    fun boostMorale(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(MilitaryInput.BOOST_MORALE, generalId, userId)
    @GetMapping("/api/commands/muster-options")
    fun muster(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(MilitaryInput.MUSTER, generalId, userId)
    @GetMapping("/api/commands/demobilize-options")
    fun demobilize(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(MilitaryInput.DEMOBILIZE, generalId, userId)

    private fun options(inputId: String, generalId: Int, userId: Long?): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.options(inputId, generalId, userId)) }
        catch (_: DomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
