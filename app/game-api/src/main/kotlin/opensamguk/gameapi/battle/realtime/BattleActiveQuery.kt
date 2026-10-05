package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.battle.realtime.BattleActiveSessionReader
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.logic.battle.realtime.BattleSide
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class BattleActiveForbidden : RuntimeException()
class BattleActiveUnavailable(cause: Throwable) : RuntimeException(cause)

/** Reads persisted sessions and projects only the caller's pinned retinue sources. */
@Service
class BattleActiveQuery(
    private val processWorld: GameApiProcessWorld,
    private val generals: GeneralResolver,
    private val sessions: BattleActiveSessionReader,
    private val store: BattleSessionStore,
    private val frozen: BattleFrozenInputCodec,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(accountId: Long, generalId: Int): List<BattleActiveEntry> {
        if (accountId <= 0 || accountId > Int.MAX_VALUE || generalId <= 0) throw BattleActiveForbidden()
        return try {
            if (generals.resolveGeneralId(accountId) != generalId) throw BattleActiveForbidden()
            val rows = sessions.forOwner(processWorld.worldId, accountId.toInt(), generalId, 101)
            check(rows.size <= 100) { "battle active list exceeds the unpaged response bound" }
            rows.map { row ->
                check(row.worldId == processWorld.worldId)
                val ticket = checkNotNull(store.ticket(row.worldId, row.battleId)) { "battle ticket unavailable" }
                check(ticket.worldId == row.worldId && ticket.battleId == row.battleId)
                check(sha256(ticket.payloadJson) == ticket.payloadSha256) { "battle payload pin mismatch" }
                val participant = checkNotNull(ticket.participants.singleOrNull {
                    it.participantId == row.participantId && it.accountId == accountId.toInt() &&
                        it.generalId == generalId && it.side == row.side &&
                        it.authorityRevision == row.authorityRevision
                }) { "battle participant pin mismatch" }
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
                BattleActiveEntry(
                    battleId = row.battleId,
                    worldId = row.worldId.value.toString(),
                    kind = kind,
                    sourcePhase = row.sourcePhase.name,
                    phase = if (row.sourcePhase == BattleSessionPhase.RUNNING) "LIVE" else row.sourcePhase.name,
                    joinDeadlineAt = row.joinDeadlineAt.takeIf { row.sourcePhase == BattleSessionPhase.JOINING },
                    observedAt = row.observedAt,
                    mySeat = BattleActiveMySeat(sourceKeys),
                )
            }
        } catch (failure: BattleActiveForbidden) {
            throw failure
        } catch (failure: RuntimeException) {
            throw BattleActiveUnavailable(failure)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
