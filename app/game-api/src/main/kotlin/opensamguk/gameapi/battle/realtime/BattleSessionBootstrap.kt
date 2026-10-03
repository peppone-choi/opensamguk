package opensamguk.gameapi.battle.realtime

import opensamguk.infra.battle.realtime.BattleSessionDiscovery
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.logic.battle.realtime.TacticalState

/** Claims expired/ready sessions and registers them with a 100ms actor cadence. */
class BattleSessionBootstrap(
    private val discovery: BattleSessionDiscovery,
    private val store: BattleSessionStore,
    private val cadence: BattleSessionCadence,
    private val initialState: (FrozenBattleTicket) -> TacticalState,
    private val owner: String,
    private val onTick: (BattleLeaseKey, BattleTickAttempt) -> Unit,
    private val onFailure: (BattleLeaseKey, Throwable) -> Unit,
) {
    init { require(owner.isNotBlank() && owner.length <= 128) }

    /** Safe to repeat: candidates are advisory and claimEpoch is the authoritative fence. */
    fun scan(limit: Int = 100): Int {
        var attached = 0
        for (ref in discovery.claimable(limit)) {
            if (cadence.isAttached(ref.worldId, ref.battleId)) continue
            val head = store.claimEpoch(ref.worldId, ref.battleId, owner, 15_000) ?: continue
            require(head.worldId == ref.worldId && head.battleId == ref.battleId)
            require(head.phase in setOf(BattleSessionPhase.JOINING, BattleSessionPhase.RUNNING,
                BattleSessionPhase.RESOLVING))
            val ticket = requireNotNull(store.ticket(ref.worldId, ref.battleId)) {
                "claimed battle ticket missing"
            }
            val key = BattleLeaseKey(ref.worldId, ref.battleId, owner, head.sessionEpoch)
            val runner = BattleSessionTickRunner(store, initialState, ref.worldId, ref.battleId,
                owner, head.sessionEpoch)
            var started = head.phase != BattleSessionPhase.JOINING
            if (cadence.attach(key, tick = {
                    if (!started) {
                        if (!store.startRun(ref.worldId, ref.battleId, owner, head.sessionEpoch))
                            BattleTickAttempt.Contended
                        else {
                            started = true
                            runner.tick()
                        }
                    } else runner.tick()
                }, onTick = { onTick(key, it) }, onFailure = { onFailure(key, it) },
                pacingMode = ticket.pacingMode)) attached++
        }
        return attached
    }
}
