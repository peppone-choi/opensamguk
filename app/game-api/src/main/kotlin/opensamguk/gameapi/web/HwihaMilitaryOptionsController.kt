package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.HwihaMilitaryOptionsService
import opensamguk.gameapi.read.HwihaDomesticForbidden
import opensamguk.logic.input.HwihaMilitaryInput
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class HwihaMilitaryOptionsController(private val service: HwihaMilitaryOptionsService) {
    @GetMapping("/api/commands/conscript-options")
    fun conscript(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(HwihaMilitaryInput.CONSCRIPT, generalId, userId)
    @GetMapping("/api/commands/raise-volunteers-options")
    fun raiseVolunteers(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(HwihaMilitaryInput.RAISE_VOLUNTEERS, generalId, userId)
    @GetMapping("/api/commands/train-options")
    fun train(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(HwihaMilitaryInput.TRAIN, generalId, userId)
    @GetMapping("/api/commands/boost-morale-options")
    fun boostMorale(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(HwihaMilitaryInput.BOOST_MORALE, generalId, userId)
    @GetMapping("/api/commands/muster-options")
    fun muster(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(HwihaMilitaryInput.MUSTER, generalId, userId)
    @GetMapping("/api/commands/demobilize-options")
    fun demobilize(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) =
        options(HwihaMilitaryInput.DEMOBILIZE, generalId, userId)

    private fun options(inputId: String, generalId: Int, userId: Long?): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.options(inputId, generalId, userId)) }
        catch (_: HwihaDomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
