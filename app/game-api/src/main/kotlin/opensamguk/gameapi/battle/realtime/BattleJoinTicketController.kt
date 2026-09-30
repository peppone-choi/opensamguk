package opensamguk.gameapi.battle.realtime

import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

data class BattleJoinTicketResponse(val joinTicket: String)

@RestController
@ConditionalOnProperty(prefix = "battle.join-ticket", name = ["enabled"], havingValue = "true")
class BattleJoinTicketController(
    private val processWorld: GameApiProcessWorld,
    private val generals: GeneralResolver,
    private val tickets: BattleJoinTicketService,
) {
    @PostMapping("/api/battles/{worldId}/{battleId}/join-ticket")
    fun issue(
        @AuthenticationPrincipal accountId: Long?,
        @PathVariable worldId: Int,
        @PathVariable battleId: String,
    ): ResponseEntity<BattleJoinTicketResponse> {
        if (accountId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (worldId != processWorld.worldId.value || battleId.isBlank() || battleId.length > 128 ||
            accountId <= 0 || accountId > Int.MAX_VALUE) return ResponseEntity.notFound().build()
        val generalId = generals.resolveGeneralId(accountId) ?: return ResponseEntity.notFound().build()
        if (generalId <= 0) return ResponseEntity.notFound().build()
        return try {
            val token = tickets.issue(WorldId(worldId), battleId, accountId.toInt(), generalId)
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BattleJoinTicketResponse(token))
        } catch (_: SecurityException) {
            ResponseEntity.notFound().build()
        }
    }
}
