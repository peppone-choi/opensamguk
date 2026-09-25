package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.*
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
class DeployController(private val precheck: DeployPrecheckService) {
    @GetMapping("/api/hwiha/deploy/options")
    fun options(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok(precheck.options(generalId, userId)) }
        catch (_: DeployReadForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
