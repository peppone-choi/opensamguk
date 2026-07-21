package opensamguk.gameapi.controller

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.dto.DiplomaticMessageCommandResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.reserve.CommandReserveService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/messages")
class DiplomaticMessageController(
    private val reserve: CommandReserveService,
    private val resolver: GeneralResolver,
) {
    @PostMapping("/{id}/accept")
    fun accept(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable id: Int,
        @RequestParam generalId: Int,
    ): ResponseEntity<DiplomaticMessageCommandResponse> {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (generalId != resolver.resolveGeneralId(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val result = reserve.publishImmediate(
            TurnDaemonCommand.AcceptDiplomaticMessage(messageId = id, generalId = generalId),
        )
        return accepted(result)
    }

    @PostMapping("/{id}/decline")
    fun decline(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable id: Int,
        @RequestParam generalId: Int,
    ): ResponseEntity<DiplomaticMessageCommandResponse> {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (generalId != resolver.resolveGeneralId(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val result = reserve.publishImmediate(
            TurnDaemonCommand.DeclineDiplomaticMessage(messageId = id, generalId = generalId),
        )
        return accepted(result)
    }

    private fun accepted(result: CommandReserveService.ReserveResult): ResponseEntity<DiplomaticMessageCommandResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(
            DiplomaticMessageCommandResponse(status = "AVAILABLE", requestId = result.requestId),
        )
}
