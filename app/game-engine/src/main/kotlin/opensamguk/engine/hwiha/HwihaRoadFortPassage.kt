package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.HwihaRoadFortState
import opensamguk.logic.world.StrategicEdgeStateSnapshot

internal object HwihaRoadFortPassage {
    fun forNation(world: InMemoryTurnWorld, passage: StrategicEdgeStateSnapshot, nationId: Int): StrategicEdgeStateSnapshot? =
        try {
            val hostile = world.listDiplomacy().filter { it.state == 0 }.mapNotNull { war ->
                when (nationId) {
                    war.fromNationId -> war.toNationId
                    war.toNationId -> war.fromNationId
                    else -> null
                }
            }.toSet()
            HwihaRoadFortState.forNation(passage, HwihaRoadFortState.read(world.getState().meta), hostile)
        }
        catch (_: IllegalArgumentException) { null }
}
