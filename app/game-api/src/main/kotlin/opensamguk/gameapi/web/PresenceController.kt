package opensamguk.gameapi.web

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.read.processRuleProfile
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.logic.input.RuleProfile
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** Explicit foreground activity; passive reads and SSE never update the delegation lease. */
@RestController
class PresenceController(
    private val resolver: GeneralResolver,
    private val worlds: WorldStateReadRepository,
    private val reserve: CommandReserveService,
) {
    @PostMapping("/api/command/presence")
    fun pulse(@AuthenticationPrincipal userId: Long?): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val generalId = resolver.resolveGeneralId(userId)
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        if (worlds.processRuleProfile() != RuleProfile.HWIHA)
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                mapOf("status" to "BLOCKED", "code" to "WRONG_RULE_PROFILE"))
        return try {
            val accepted = reserve.publishImmediate(
                TurnDaemonCommand.PresencePulse(generalId, userId.toInt()), userId.toInt())
            ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(
                mapOf("status" to "AVAILABLE", "requestId" to accepted.requestId))
        } catch (denied: AdmissionDenied) {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                mapOf("status" to "BLOCKED", "code" to denied.code, "reason" to denied.message))
        }
    }
}
