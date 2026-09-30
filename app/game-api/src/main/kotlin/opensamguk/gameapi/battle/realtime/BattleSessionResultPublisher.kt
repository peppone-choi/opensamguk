package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensamguk.infra.battle.realtime.BattleResultRecord
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.logic.battle.realtime.TacticalBattle

/** Publishes the terminal, epoch-fenced battle result without touching campaign state. */
class BattleSessionResultPublisher(private val store: BattleSessionStore) {
    fun publish(key: BattleLeaseKey, resolved: BattleTickAttempt.Resolved): Boolean {
        val state = resolved.state
        val outcome = requireNotNull(state.outcome) { "battle result lacks outcome" }
        val ticket = requireNotNull(store.ticket(key.worldId, key.battleId)) { "battle ticket missing" }
        val events = store.eventsAfter(key.worldId, key.battleId, 0)
        require(events.lastOrNull()?.eventSeq == resolved.eventSeq) {
            "battle input changed after terminal tick"
        }
        require(events.map { it.eventSeq } == (1L..resolved.eventSeq).toList()) {
            "battle result event sequence gap"
        }
        require(events.all { sha(it.payloadJson) == it.payloadSha256 }) {
            "battle result event checksum mismatch"
        }
        val stateHash = TacticalBattle.stateHash(state)
        val resultJson = buildJsonObject {
            put("schemaVersion", 1)
            put("worldId", key.worldId.value)
            put("battleId", key.battleId)
            put("pacingMode", ticket.pacingMode.name)
            put("ruleSha256", ticket.ruleSha256)
            put("catalogSha256", ticket.catalogSha256)
            put("terrainSha256", ticket.terrainSha256)
            put("lockGeneration", ticket.lockGeneration)
            put("lockSetRevision", ticket.lockSetRevision)
            put("outcome", outcome.name)
            put("tick", state.tick)
            put("battlefieldId", state.battlefield.id)
            put("gateHp", state.gateHp)
            put("stateHash", stateHash)
            put("units", buildJsonArray {
                state.units.sortedBy { it.retinue.id }.forEach { unit ->
                    add(buildJsonObject {
                        put("retinueId", unit.retinue.id)
                        put("generalId", unit.retinue.general.id)
                        put("side", unit.side.name)
                        put("slot", unit.slot.name)
                        put("troops", unit.troops)
                        put("morale", unit.morale)
                        put("escaped", unit.escaped)
                    })
                }
            })
        }.toString()
        val replayMaterial = buildString {
            append(ticket.payloadSha256).append('\n')
            events.forEach {
                append(it.eventSeq).append(':').append(it.sessionEpoch).append(':')
                    .append(it.tick).append(':').append(it.effectiveTick).append(':')
                    .append(it.type).append(':').append(it.payloadSha256).append('\n')
            }
            append(stateHash)
        }
        val result = BattleResultRecord(key.worldId, key.battleId, key.epoch, key.owner, 1,
            resultJson, sha(resultJson), sha(replayMaterial), ticket.lockGeneration,
            ticket.lockSetRevision, ticket.pacingMode)
        if (store.publishResult(result)) return true
        return store.head(key.worldId, key.battleId)?.phase in
            setOf(BattleSessionPhase.RESULT_PENDING, BattleSessionPhase.APPLIED)
    }

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
