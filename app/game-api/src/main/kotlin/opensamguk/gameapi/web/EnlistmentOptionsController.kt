package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.EnlistmentOptionsForbidden
import opensamguk.gameapi.precheck.EnlistmentPrecheckService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class EnlistmentOptionsController(private val precheck: EnlistmentPrecheckService) {
    @GetMapping("/api/commands/enlistment-options")
    fun options(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            ResponseEntity.ok(precheck.options(generalId, userId))
        } catch (_: EnlistmentOptionsForbidden) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }
}
