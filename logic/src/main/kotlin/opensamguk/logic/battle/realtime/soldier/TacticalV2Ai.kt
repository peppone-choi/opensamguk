package opensamguk.logic.battle.realtime.soldier

import opensamguk.logic.battle.realtime.BattleSide

/** Pure shared NPC/disconnected-side intent generator. No mutable RNG or wire authority. */
object TacticalV2Ai {
    fun orders(state: TacticalV2BattleState, side: BattleSide): List<TacticalV2SoldierCommand> {
        if (state.status != TacticalV2SoldierRunStatus.RUNNING) return emptyList()
        val facts = state.context.facts.associateBy { it.source.key }
        val enemies = state.soldiers.filter { it.alive && facts.getValue(it.sourceKey).source.side != side }
        return state.soldiers.filter { it.alive && facts.getValue(it.sourceKey).source.side == side &&
            !facts.getValue(it.sourceKey).source.stationary }.mapNotNull { soldier ->
            // C24: automatic defense only. Idle NPC/disconnected units never initiate a new offensive march.
            val radius = TacticalV2Battle.range(facts.getValue(soldier.sourceKey).kind, state.context.rules).raw
            val target = enemies.filter { it.targetId == soldier.id &&
                distanceSquared(soldier.pos, it.pos) <= radius * radius }.minWithOrNull(
                compareBy<TacticalV2Soldier>({ distanceSquared(soldier.pos, it.pos) }, { it.id }))
            TacticalV2SoldierCommand(listOf(soldier.id), if (target == null) TacticalV2SoldierOrder.Stop
                else TacticalV2SoldierOrder.Attack(target.id))
        }
    }

    /** Stateless hash randomness for future approved stochastic rules; no sequencing dependency. */
    fun roll(seed: Long, tick: Long, soldierId: Long, purpose: String): Long {
        require(tick >= 0 && soldierId > 0)
        var value = seed xor tick.rotateLeft(17) xor soldierId.rotateLeft(31)
        for (byte in purpose.toByteArray(Charsets.UTF_8)) {
            value = (value xor (byte.toLong() and 255)) * 1099511628211L
        }
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }
}
