package opensamguk.gameapi.web

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.precheck.DispatchReadForbidden
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.AdmissionDenied
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
class CourtController(private val reserve: CommandReserveService) {
    @PostMapping("/api/commands/court/{name}")
    fun submit(@AuthenticationPrincipal userId: Long?, @PathVariable name: String,
        @RequestParam generalId: Int, @RequestBody raw: String): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            val inputId = "court.$name"
            val accepted = reserve.publishImmediate(TurnDaemonCommand.ImmediateInput("", generalId,
                userId.toInt(), inputId, raw), userId.toInt())
            ResponseEntity.status(HttpStatus.ACCEPTED).body(mapOf("status" to "AVAILABLE",
                "requestId" to accepted.requestId, "inputId" to inputId))
        } catch (_: DispatchReadForbidden) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        } catch (denied: AdmissionDenied) {
            ResponseEntity.ok(mapOf("status" to "BLOCKED", "code" to denied.code, "reason" to denied.message))
        }
    }
}
