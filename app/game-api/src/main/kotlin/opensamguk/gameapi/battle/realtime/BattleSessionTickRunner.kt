package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.BattleCheckpoint
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.infra.battle.realtime.BattleTransition
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.logic.battle.realtime.TacticalBattle
import opensamguk.logic.battle.realtime.TacticalRules
import opensamguk.logic.battle.realtime.TacticalState
import opensamguk.logic.battle.realtime.TacticalStateCodec

sealed interface BattleTickAttempt {
    data class Advanced(val state: TacticalState, val eventSeq: Long,
                        val checkpointed: Boolean) : BattleTickAttempt
    data class Resolved(val state: TacticalState, val eventSeq: Long) : BattleTickAttempt
    data object Contended : BattleTickAttempt
    data object NotRunning : BattleTickAttempt
}

/** One leased battle actor. The caller invokes [tick] on a 100ms cadence and owns lease renewal. */
class BattleSessionTickRunner(
    private val store: BattleSessionStore,
    private val initialState: (FrozenBattleTicket) -> TacticalState,
    private val worldId: WorldId,
    private val battleId: String,
    private val owner: String,
    private val epoch: Long,
) {
    init { require(battleId.isNotBlank() && owner.isNotBlank() && epoch > 0) }

    private var cached: BattleTimelineState? = null

    @Synchronized
    fun tick(): BattleTickAttempt {
        val head = store.head(worldId, battleId) ?: return BattleTickAttempt.NotRunning
        if (head.phase !in setOf(BattleSessionPhase.RUNNING, BattleSessionPhase.RESOLVING) || head.leaseOwner != owner ||
            head.sessionEpoch != epoch) {
            cached = null
            return BattleTickAttempt.NotRunning
        }
        val base = cached?.takeIf { it.state.tick == head.currentTick } ?: restore(head.currentTick,
            head.latestEventSeq) ?: return BattleTickAttempt.Contended
        val tail = store.eventsAfter(worldId, battleId, base.consumedEventSeq)
        val current = BattleEventTimeline.replay(base.state, base.consumedEventSeq, tail,
            head.currentTick, requireAutomaticOrders = true)
        if (head.phase == BattleSessionPhase.RESOLVING) {
            require(current.state.outcome != null) { "resolving session lacks terminal state" }
            cached = current
            return BattleTickAttempt.Resolved(current.state, current.consumedEventSeq)
        }
        require(current.state.tick < TacticalRules.CANON.battleTicks || current.state.outcome != null) {
            "battle maximum tick lacks resolution"
        }
        if (current.state.outcome != null) {
            cached = current
            return BattleTickAttempt.Resolved(current.state, current.consumedEventSeq)
        }
        val aiPayload = buildJsonObject {
            put("schemaVersion", 1)
            put("orders", buildJsonArray {
                TacticalBattle.automaticOrders(current.state).forEach { order ->
                    add(buildJsonObject {
                        put("side", order.side.name)
                        put("slot", order.slot.name)
                        put("order", order.order.name)
                        put("rally", order.rally.name)
                    })
                }
            })
        }.toString()
        if (store.appendTransition(BattleTransition(worldId, battleId, epoch, owner,
                "ai-orders-${head.currentTick}", "AI_ORDERS", null, head.currentTick,
                head.currentTick + 1, aiPayload, sha(aiPayload))) == null) {
            cached = null
            return BattleTickAttempt.Contended
        }
        val nextTail = store.eventsAfter(worldId, battleId, current.consumedEventSeq)
        val next = BattleEventTimeline.replay(current.state, current.consumedEventSeq,
            nextTail, head.currentTick + 1, requireAutomaticOrders = true)
        val observedSeq = nextTail.lastOrNull()?.eventSeq ?: current.consumedEventSeq
        if (observedSeq < head.latestEventSeq || next.consumedEventSeq != observedSeq ||
            !(if (next.state.outcome != null)
                store.advanceResolvedTick(worldId, battleId, owner, epoch, head.currentTick, observedSeq)
              else store.advanceTick(worldId, battleId, owner, epoch, head.currentTick, observedSeq))) {
            cached = null
            return BattleTickAttempt.Contended
        }
        cached = next
        val due = next.state.tick % 50 == 0 || next.state.outcome != null
        val checkpointed = due && store.checkpoint(BattleCheckpoint(worldId, battleId, epoch,
            owner, next.state.tick, next.consumedEventSeq, next.stateHash,
            TacticalStateCodec.encode(next.state)))
        return if (next.state.outcome != null)
            BattleTickAttempt.Resolved(next.state, next.consumedEventSeq)
        else BattleTickAttempt.Advanced(next.state, next.consumedEventSeq, checkpointed)
    }

    private fun restore(durableTick: Int, latestEventSeq: Long): BattleTimelineState? {
        val ticket = requireNotNull(store.ticket(worldId, battleId)) { "battle ticket missing" }
        require(ticket.worldId == worldId && ticket.battleId == battleId)
        require(sha(ticket.payloadJson) == ticket.payloadSha256) { "battle ticket checksum mismatch" }
        val frozen = initialState(ticket)
        require(frozen.tick == 0 && frozen.seed == ticket.seed)
        val checkpoint = store.latestCheckpoint(worldId, battleId)
            ?: return BattleTimelineState(frozen, 0)
        // Head and checkpoint are separate reads. Another actor may have advanced between them.
        if (checkpoint.tick > durableTick || checkpoint.eventSeq > latestEventSeq) return null
        val state = TacticalStateCodec.decode(checkpoint.compressedState, checkpoint.stateHash)
        require(state.tick == checkpoint.tick && state.seed == frozen.seed &&
            state.battlefield == frozen.battlefield && state.gateRow == frozen.gateRow &&
            state.gateCol == frozen.gateCol &&
            state.units.map { it.side to it.retinue }.sortedBy { it.second.id } ==
                frozen.units.map { it.side to it.retinue }.sortedBy { it.second.id }) {
            "battle checkpoint does not match frozen ticket"
        }
        return BattleTimelineState(state, checkpoint.eventSeq)
    }

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
