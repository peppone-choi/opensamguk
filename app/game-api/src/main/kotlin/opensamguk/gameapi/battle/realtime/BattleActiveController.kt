package opensamguk.gameapi.battle.realtime

import java.time.Instant
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.battle.realtime.BattleActiveSessionReader
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.logic.battle.realtime.BattleSide
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class BattleActiveSeat(val retinueId: Int, val slot: String, val generalId: Int)

data class BattleActiveEntry(
    val battleId: String,
    val worldId: Int,
    val kind: String,
    val pacingMode: String,
    val joinDeadlineAt: Instant,
    val side: String,
    val seats: List<BattleActiveSeat>,
    val state: String,
)

/** Lists only battles in which the authenticated account's current general is a frozen participant. */
@RestController
class BattleActiveController(
    private val processWorld: GameApiProcessWorld,
    private val generals: GeneralResolver,
    private val sessions: BattleActiveSessionReader,
    private val store: BattleSessionStore,
    private val frozen: BattleFrozenInputCodec,
) {
    @GetMapping("/api/battles/active")
    fun active(@AuthenticationPrincipal accountId: Long?,
               @RequestParam generalId: Int): ResponseEntity<List<BattleActiveEntry>> {
        if (accountId == null || accountId <= 0 || accountId > Int.MAX_VALUE)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (generalId <= 0 || generals.resolveGeneralId(accountId) != generalId)
            return ResponseEntity.notFound().build()
        val owner = accountId.toInt()
        val entries = sessions.forOwner(processWorld.worldId, owner, generalId, 100).mapNotNull { row ->
            if (row.worldId != processWorld.worldId) return@mapNotNull null
            val ticket = store.ticket(row.worldId, row.battleId) ?: return@mapNotNull null
            require(ticket.worldId == row.worldId && ticket.battleId == row.battleId &&
                MessageDigest.getInstance("SHA-256").digest(ticket.payloadJson.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) } == ticket.payloadSha256)
            val participant = ticket.participants.singleOrNull {
                it.participantId == row.participantId && it.accountId == owner &&
                    it.generalId == generalId && it.side == row.side &&
                    it.authorityRevision == row.authorityRevision
            } ?: return@mapNotNull null
            val kind = when (Json.parseToJsonElement(ticket.payloadJson).jsonObject
                .getValue("kind").jsonPrimitive.content) {
                "ENCOUNTER" -> "FIELD"
                "SIEGE" -> "SIEGE"
                "PERSONAL_DUEL" -> "DUEL"
                else -> error("unsupported battle kind")
            }
            val seats = if (kind == "DUEL") emptyList() else {
                val side = BattleSide.valueOf(participant.side)
                frozen.initialState(ticket).units.asSequence().filter { it.side == side }
                    .map { BattleActiveSeat(it.retinue.id, it.slot.name, it.retinue.general.id) }
                    .toList()
            }
            BattleActiveEntry(row.battleId, row.worldId.value, kind, "REALTIME", row.joinDeadlineAt,
                participant.side, seats, row.phase.name)
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(entries)
    }
}
