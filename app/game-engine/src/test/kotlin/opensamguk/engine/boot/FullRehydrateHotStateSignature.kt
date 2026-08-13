package opensamguk.engine.boot

import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Troop
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState

internal fun fullRehydrateHotStateSignature(world: InMemoryTurnWorld): FullRehydrateHotStateSignature =
    FullRehydrateHotStateSignature(
        state = world.getState().copy(id = 0),
        generals = world.listGenerals().sortedBy { it.id }.map { it.copy(initialTurns = emptyList()) },
        cities = world.listCities().sortedBy { it.id },
        nations = world.listNations().sortedBy { it.id },
        troops = world.listTroops().sortedBy { it.id },
        diplomacy = world.listDiplomacy().sortedWith(compareBy({ it.fromNationId }, { it.toNationId })),
        accessLogs = world.listAccessLogs().sortedBy { it.generalId },
    )

internal data class FullRehydrateHotStateSignature(
    val state: TurnWorldState,
    val generals: List<TurnGeneral>,
    val cities: List<City>,
    val nations: List<Nation>,
    val troops: List<Troop>,
    val diplomacy: List<TurnDiplomacy>,
    val accessLogs: List<GeneralAccessLog>,
)
