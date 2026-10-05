package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.battle.realtime.BattleActiveSessionReader
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.logic.battle.realtime.BattleSide
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class BattleActiveSourceKey(val kind: String, val sourceId: String)
data class BattleActiveMySeat(val sourceKeys: List<BattleActiveSourceKey>)

/** Nullable fields have no producer on the current main; the reason is part of the wire proposal. */
data class BattleActiveEntry(
    val battleId: String,
    val worldId: String,
    val kind: String,
    val sourcePhase: String,
    val phase: String,
    val joinDeadlineAt: Instant?,
    val observedAt: Instant,
    val mySeat: BattleActiveMySeat,
    val pacingMode: String? = null,
    val pacingModeUnavailableReason: String = "SOURCE_NOT_AVAILABLE",
    val controller: String? = null,
    val controllerUnavailableReason: String = "SOURCE_NOT_AVAILABLE",
    val place: String? = null,
    val placeUnavailableReason: String = "SOURCE_NOT_AVAILABLE",
    val sides: List<String>? = null,
    val sidesUnavailableReason: String = "SOURCE_NOT_AVAILABLE",
    val replayId: String? = null,
    val replayIdUnavailableReason: String = "SOURCE_NOT_AVAILABLE",
)

/** Lists persisted battles only for the authenticated account's current general. */
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        val rows = sessions.forOwner(processWorld.worldId, accountId.toInt(), generalId, 101)
        check(rows.size <= 100) { "battle active list exceeds the unpaged response bound" }
        val entries = rows.map { row ->
            check(row.worldId == processWorld.worldId)
            val ticket = checkNotNull(store.ticket(row.worldId, row.battleId)) { "battle ticket unavailable" }
            check(ticket.worldId == row.worldId && ticket.battleId == row.battleId)
            check(sha256(ticket.payloadJson) == ticket.payloadSha256) { "battle payload pin mismatch" }
            val participant = ticket.participants.singleOrNull {
                it.participantId == row.participantId && it.accountId == accountId.toInt() &&
                    it.generalId == generalId && it.side == row.side &&
                    it.authorityRevision == row.authorityRevision
            }
            checkNotNull(participant) { "battle participant pin mismatch" }

            val kind = when (Json.parseToJsonElement(ticket.payloadJson).jsonObject
                .getValue("kind").jsonPrimitive.content) {
                "ENCOUNTER" -> "FIELD"
                "SIEGE" -> "SIEGE"
                "PERSONAL_DUEL" -> "DUEL"
                else -> error("unsupported battle kind")
            }
            val sourceKeys = if (kind == "DUEL") emptyList() else {
                val side = BattleSide.valueOf(participant.side)
                frozen.initialState(ticket).units.asSequence()
                    .filter { it.side == side && it.retinue.general.id == generalId }
                    .map { BattleActiveSourceKey("RETINUE", it.retinue.id.toString()) }
                    .distinct().sortedBy { it.sourceId.toInt() }.toList()
            }
            val phase = when (row.sourcePhase) {
                BattleSessionPhase.RUNNING -> "LIVE"
                else -> row.sourcePhase.name
            }
            BattleActiveEntry(
                battleId = row.battleId,
                worldId = row.worldId.value.toString(),
                kind = kind,
                sourcePhase = row.sourcePhase.name,
                phase = phase,
                joinDeadlineAt = row.joinDeadlineAt.takeIf { row.sourcePhase == BattleSessionPhase.JOINING },
                observedAt = row.observedAt,
                mySeat = BattleActiveMySeat(sourceKeys),
            )
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(entries)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
