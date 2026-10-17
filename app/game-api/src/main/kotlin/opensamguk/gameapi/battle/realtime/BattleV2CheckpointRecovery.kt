package opensamguk.gameapi.battle.realtime

import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.BattleSessionHead
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.logic.battle.realtime.soldier.TacticalV2BattleCodec
import opensamguk.logic.battle.realtime.soldier.TacticalV2BattleContext
import opensamguk.logic.battle.realtime.soldier.TacticalV2BattleState

/** Internal recovery only; the caller must bind the committed context to this battle first. */
internal class BattleV2CheckpointRecovery(
    private val store: BattleSessionStore,
    private val worldId: WorldId,
    private val battleId: String,
    private val owner: String,
    private val epoch: Long,
    private val leaseMillis: Long,
) {
    init {
        require(battleId.isNotBlank() && owner.isNotBlank() && epoch > 0 && leaseMillis in 1..60_000)
    }

    internal data class Restored(val state: TacticalV2BattleState, val eventSeq: Long)

    /**
     * Null means no exact, currently leased checkpoint is available. Corrupt bytes/pins throw.
     * Lease renewal uses the database clock; a returned state still requires fenced future writes.
     * No tail replay, initial-state fallback, tick advancement or actor activation occurs here.
     */
    fun restore(context: TacticalV2BattleContext): Restored? {
        val head = store.head(worldId, battleId)?.takeIf(::owned) ?: return null
        if (head.latestSnapshotSeq <= 0) return null
        val checkpoint = store.latestCheckpoint(worldId, battleId) ?: return null
        require(checkpoint.worldId == worldId && checkpoint.battleId == battleId) {
            "battle checkpoint identity mismatch"
        }
        if (checkpoint.sessionEpoch !in 1..epoch ||
            (checkpoint.sessionEpoch == epoch && checkpoint.leaseOwner != owner)) return null
        // An older writer's checkpoint is valid after claim; an unconsumed tail is not.
        if (checkpoint.tick != head.currentTick || checkpoint.eventSeq != head.latestEventSeq) return null
        val state = TacticalV2BattleCodec.decode(checkpoint.compressedState.copyOf(), context, checkpoint.stateHash)
        require(state.tick == checkpoint.tick.toLong()) { "battle checkpoint tick mismatch" }
        if (!store.renewLease(worldId, battleId, owner, epoch, leaseMillis)) return null
        val latest = store.head(worldId, battleId) ?: return null
        // Renewal may change lease_until, but none of the durable recovery coordinates may move.
        if (!owned(latest) || head.copy(leaseUntil = latest.leaseUntil) != latest) return null
        return Restored(state, checkpoint.eventSeq)
    }

    private fun owned(head: BattleSessionHead): Boolean =
        head.worldId == worldId && head.battleId == battleId && head.sessionEpoch == epoch &&
            head.leaseOwner == owner && head.leaseUntil != null && head.currentTick >= 0 &&
            head.latestEventSeq >= 0 &&
            head.phase in setOf(BattleSessionPhase.RUNNING, BattleSessionPhase.RESOLVING)
}
