package opensamguk.gameapi.battle.replay

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import opensamguk.gameapi.battle.realtime.BattleEventTimeline
import opensamguk.gameapi.battle.realtime.BattleFrozenInputCodec
import opensamguk.gameapi.battle.realtime.BattleResolutionKind
import opensamguk.infra.battle.realtime.BattleEventRecord
import opensamguk.infra.battle.realtime.BattlePacingMode
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.replay.BattleReplayArchive
import opensamguk.logic.battle.realtime.TacticalBattle
import opensamguk.logic.battle.realtime.TacticalState
import opensamguk.logic.battle.realtime.TacticalStateCodec

/**
 * Internal archive verification only. It neither reads for a caller nor grants admission/disclosure.
 * The future caller-facing query must enforce its approved world ACL before obtaining this archive.
 */
internal class BattleReplayQuery(private val frozen: BattleFrozenInputCodec) {
    fun verify(archive: BattleReplayArchive): BattleReplayView = try {
        verifyStored(archive)
    } catch (failure: Mismatch) {
        BattleReplayView(failure.reason)
    }

    private fun verifyStored(archive: BattleReplayArchive): BattleReplayView {
        val (ticket, head) = archive
        match(BattleReplayReason.PIN_MISMATCH, ticket.worldId == head.worldId && ticket.battleId == head.battleId)
        match(BattleReplayReason.NOT_APPLIED, head.phase == BattleSessionPhase.APPLIED &&
            archive.results.size == 1 && archive.results.single().status == "APPLIED" &&
            archive.results.single().appliedAt != null)
        match(BattleReplayReason.PIN_MISMATCH, sha(ticket.payloadJson) == ticket.payloadSha256)
        val root = parsed(ticket.payloadJson, BattleReplayReason.PIN_MISMATCH)
        val schema = checked(BattleReplayReason.PIN_MISMATCH) { root.getValue("schemaVersion").jsonPrimitive.int }
        val kind = checked(BattleReplayReason.PIN_MISMATCH) { root.getValue("kind").jsonPrimitive.content }
        match(BattleReplayReason.UNSUPPORTED_SCHEMA, schema == 1 && kind in setOf("ENCOUNTER", "SIEGE"))
        val initial = checked(BattleReplayReason.PIN_MISMATCH) { frozen.initialState(ticket) }
        val stored = archive.results.single()
        val result = stored.record
        match(BattleReplayReason.RESULT_MISMATCH, result.worldId == ticket.worldId && result.battleId == ticket.battleId &&
            result.sessionEpoch == head.sessionEpoch && result.lockGeneration == ticket.lockGeneration &&
            result.lockSetRevision == ticket.lockSetRevision && result.pacingMode == ticket.pacingMode &&
            sha(result.resultJson) == result.resultSha256)
        val body = parsed(result.resultJson, BattleReplayReason.RESULT_MISMATCH)
        val resolution = checked(BattleReplayReason.RESULT_MISMATCH) {
            BattleResolutionKind.valueOf(body.getValue("resolution").jsonPrimitive.content)
        }
        val inputs = verifiedInputs(archive)
        val replayed = checked(BattleReplayReason.INPUT_MISMATCH) {
            BattleEventTimeline.replay(initial, 0, inputs, head.currentTick, requireAutomaticOrders = true)
        }
        val state = when (resolution) {
            BattleResolutionKind.TACTICAL -> {
                match(BattleReplayReason.RESULT_MISMATCH, replayed.state.outcome != null &&
                    replayed.consumedEventSeq == (inputs.lastOrNull()?.eventSeq ?: 0L))
                replayed.state
            }
            BattleResolutionKind.TIMEOUT_SCORE -> {
                match(BattleReplayReason.RESULT_MISMATCH, ticket.pacingMode == BattlePacingMode.REALTIME &&
                    replayed.state.outcome == null)
                replayed.state.copy(outcome = TacticalBattle.timeoutOutcome(replayed.state))
            }
        }
        verifyCheckpoint(archive, initial, inputs)
        match(BattleReplayReason.RESULT_MISMATCH, body == expectedResult(archive, state, resolution))
        val stateHash = TacticalBattle.stateHash(state)
        val material = buildString {
            append(ticket.payloadSha256).append('\n')
            inputs.forEach {
                append(it.eventSeq).append(':').append(it.sessionEpoch).append(':')
                    .append(it.tick).append(':').append(it.effectiveTick).append(':')
                    .append(it.type).append(':').append(it.payloadSha256).append('\n')
            }
            append(resolution.name).append('\n').append(stateHash)
        }
        match(BattleReplayReason.REPLAY_HASH_MISMATCH, sha(material) == result.replayHash)
        // No K2 asset pin or durable campaign ACK producer exists in this source revision.
        return BattleReplayView(BattleReplayReason.PIN_UNAVAILABLE, BattleReplayVerification(
            ticket.battleId, result.resultRevision, checkNotNull(stored.appliedAt),
            result.replayHash, stateHash, inputs.size))
    }

    private fun verifiedInputs(archive: BattleReplayArchive): List<BattleEventRecord> {
        val events = archive.events
        val result = archive.results.single().record
        val head = archive.head
        match(BattleReplayReason.INPUT_MISMATCH, events.isNotEmpty() && events.size.toLong() == head.latestEventSeq &&
            events.withIndex().all { (index, event) -> event.eventSeq == index + 1L } &&
            events.zipWithNext().all { (left, right) -> left.sessionEpoch <= right.sessionEpoch })
        val terminal = events.last()
        match(BattleReplayReason.RESULT_MISMATCH, terminal.type == "BATTLE_RESOLVED" &&
            events.dropLast(1).none { it.type == "BATTLE_RESOLVED" } &&
            terminal.sessionEpoch == result.sessionEpoch && terminal.tick == head.currentTick &&
            terminal.effectiveTick == head.currentTick && terminal.payloadJson == result.resultJson &&
            terminal.payloadSha256 == result.resultSha256)
        val inputs = events.dropLast(1)
        match(BattleReplayReason.INPUT_MISMATCH, inputs.all { sha(it.payloadJson) == it.payloadSha256 })
        return inputs
    }

    private fun verifyCheckpoint(archive: BattleReplayArchive, initial: TacticalState, inputs: List<BattleEventRecord>) {
        val checkpoint = archive.checkpoint ?: return
        match(BattleReplayReason.INPUT_MISMATCH, checkpoint.worldId == archive.ticket.worldId &&
            checkpoint.battleId == archive.ticket.battleId && checkpoint.tick <= archive.head.currentTick &&
            checkpoint.sessionEpoch in 1..archive.head.sessionEpoch)
        val decoded = checked(BattleReplayReason.INPUT_MISMATCH) {
            TacticalStateCodec.decode(checkpoint.compressedState, checkpoint.stateHash)
        }
        val fromStart = checked(BattleReplayReason.INPUT_MISMATCH) {
            BattleEventTimeline.replay(initial, 0, inputs, checkpoint.tick, requireAutomaticOrders = true)
        }
        match(BattleReplayReason.INPUT_MISMATCH, decoded.tick == checkpoint.tick &&
            TacticalBattle.stateHash(decoded) == fromStart.stateHash && checkpoint.eventSeq == fromStart.consumedEventSeq)
    }

    /** Mirrors the existing publisher's v1 storage fields; this is not a new public DTO/format. */
    private fun expectedResult(archive: BattleReplayArchive, state: TacticalState,
                               resolution: BattleResolutionKind): JsonObject = buildJsonObject {
        val ticket = archive.ticket
        put("schemaVersion", 1)
        put("worldId", ticket.worldId.value)
        put("battleId", ticket.battleId)
        put("pacingMode", ticket.pacingMode.name)
        put("ruleSha256", ticket.ruleSha256)
        put("catalogSha256", ticket.catalogSha256)
        put("terrainSha256", ticket.terrainSha256)
        put("lockGeneration", ticket.lockGeneration)
        put("lockSetRevision", ticket.lockSetRevision)
        put("outcome", checkNotNull(state.outcome).name)
        put("resolution", resolution.name)
        put("tick", state.tick)
        put("battlefieldId", state.battlefield.id)
        put("gateHp", state.gateHp)
        put("stateHash", TacticalBattle.stateHash(state))
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
    }

    private class Mismatch(val reason: BattleReplayReason) : RuntimeException()
    private fun match(reason: BattleReplayReason, condition: Boolean) { if (!condition) throw Mismatch(reason) }
    private fun <T> checked(reason: BattleReplayReason, read: () -> T): T = try { read() }
        catch (_: IllegalArgumentException) { throw Mismatch(reason) }
        catch (_: IllegalStateException) { throw Mismatch(reason) }
        catch (_: NoSuchElementException) { throw Mismatch(reason) }
    private fun parsed(text: String, reason: BattleReplayReason) = checked(reason) {
        Json.parseToJsonElement(text).jsonObject
    }
    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
