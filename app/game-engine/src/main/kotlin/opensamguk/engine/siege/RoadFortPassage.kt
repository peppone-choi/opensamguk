package opensamguk.engine.siege

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.RoadFortState
import opensamguk.logic.world.StrategicEdgeStateSnapshot

internal object RoadFortPassage {
    fun forNation(world: InMemoryTurnWorld, passage: StrategicEdgeStateSnapshot, nationId: Int): StrategicEdgeStateSnapshot? =
        try {
            val hostile = world.listDiplomacy().filter { it.state == 0 }.mapNotNull { war ->
                when (nationId) {
                    war.fromNationId -> war.toNationId
                    war.toNationId -> war.fromNationId
                    else -> null
                }
            }.toSet()
            RoadFortState.forNation(passage, RoadFortState.read(world.getState().meta), hostile)
        }
        catch (_: IllegalArgumentException) { null }
}
