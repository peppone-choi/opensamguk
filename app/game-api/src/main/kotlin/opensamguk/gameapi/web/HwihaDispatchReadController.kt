package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.*
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
class HwihaDispatchReadController(private val service: HwihaDispatchPrecheckService) {
    @GetMapping("/api/commands/dispatches")
    fun pending(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(401).build()
        return try { ResponseEntity.ok(service.pending(generalId, userId)) }
        catch (_: DispatchReadForbidden) { ResponseEntity.status(403).build() }
    }
}
