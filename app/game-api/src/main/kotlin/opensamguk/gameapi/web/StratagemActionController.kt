package opensamguk.gameapi.web

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.precheck.StratagemActionOptionsService
import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.AdmissionDenied
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
class StratagemActionController(private val reserve: CommandReserveService,
    private val options: StratagemActionOptionsService) {
    @PostMapping("/api/commands/stratagem/{name}")
    fun submit(@AuthenticationPrincipal userId: Long?, @PathVariable name: String,
        @RequestParam generalId: Int, @RequestBody raw: String): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            val inputId = "stratagem.$name"
            val accepted = reserve.publishImmediate(TurnDaemonCommand.ImmediateInput("", generalId,
                userId.toInt(), inputId, raw), userId.toInt())
            ResponseEntity.status(HttpStatus.ACCEPTED).body(mapOf("status" to "AVAILABLE",
                "requestId" to accepted.requestId, "inputId" to inputId))
        } catch (_: DomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
          catch (denied: AdmissionDenied) { ResponseEntity.ok(mapOf("status" to "BLOCKED", "code" to denied.code,
              "reason" to denied.message)) }
    }

    @GetMapping("/api/commands/legacy-stratagem-options")
    fun options(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int,
        @RequestParam inputId: String): ResponseEntity<Any> {
        if (userId == null || userId <= 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(options.options(generalId, userId, inputId)) }
        catch (_: DomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
