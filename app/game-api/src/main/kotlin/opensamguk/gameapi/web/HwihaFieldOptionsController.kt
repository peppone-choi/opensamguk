package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.HwihaFieldOptionsService
import opensamguk.gameapi.read.HwihaDomesticForbidden
import opensamguk.logic.domestic.FieldInput
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class HwihaFieldOptionsController(private val service: HwihaFieldOptionsService) {
    @GetMapping("/api/commands/farm-options")
    fun farm(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) = options(FieldInput.FARM, generalId, userId)
    @GetMapping("/api/commands/commerce-options")
    fun commerce(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) = options(FieldInput.COMMERCE, generalId, userId)
    @GetMapping("/api/commands/fortify-options")
    fun fortify(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) = options(FieldInput.FORTIFY, generalId, userId)
    @GetMapping("/api/commands/repair-wall-options")
    fun repairWall(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) = options(FieldInput.REPAIR_WALL, generalId, userId)
    @GetMapping("/api/commands/security-options")
    fun security(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) = options(FieldInput.SECURITY, generalId, userId)
    @GetMapping("/api/commands/settle-options")
    fun settle(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) = options(FieldInput.SETTLE, generalId, userId)
    @GetMapping("/api/commands/select-residents-options")
    fun selectResidents(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) = options(FieldInput.SELECT_RESIDENTS, generalId, userId)
    @GetMapping("/api/commands/tour-options")
    fun tour(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int) = options(FieldInput.TOUR, generalId, userId)

    private fun options(inputId: String, generalId: Int, userId: Long?): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.options(inputId, generalId, userId)) }
        catch (_: HwihaDomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
