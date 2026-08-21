package opensamguk.engine.turn

import opensamguk.common.world.WorldId
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessNationCommandBallyeongTest {

    @Test
    fun `ballyeong increments last deployment when destination general is in another turn bucket`() {
        val actorTurnTime = Instant.parse("0200-03-01T00:10:00Z")
        val destTurnTime = Instant.parse("0200-03-01T02:10:00Z")
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 3,
                    tickSeconds = 7_200,
                    lastTurnTime = actorTurnTime,
                    config = linkedMapOf("mapName" to "che"),
                ),
                generals = listOf(
                    TurnGeneral(
                        id = 10,
                        name = "유비",
                        nationId = 1,
                        cityId = 1,
                        troopId = 0,
                        stats = GeneralStats(80, 70, 60),
                        experience = 0,
                        dedication = 0,
                        officerLevel = 12,
                        turnTime = actorTurnTime,
                    ),
                    TurnGeneral(
                        id = 14,
                        name = "공융",
                        nationId = 1,
                        cityId = 1,
                        troopId = 0,
                        stats = GeneralStats(50, 50, 50),
                        experience = 0,
                        dedication = 0,
                        officerLevel = 1,
                        turnTime = destTurnTime,
                        meta = linkedMapOf("aux" to linkedMapOf("max_domestic_critical" to 496)),
                    ),
                ),
                cities = listOf(
                    City(id = 1, name = "업", nationId = 1, level = 8, supplyState = 1),
                    City(id = 9, name = "남피", nationId = 1, level = 7, supplyState = 1),
                ),
                nations = listOf(
                    Nation(
                        id = 1,
                        name = "후한",
                        color = "#000000",
                        capitalCityId = 1,
                        meta = linkedMapOf("capset" to 0),
                    ),
                ),
                worldId = WorldId(1),
            ),
        )
        val processor = ProcessNationCommand(
            world = world,
            recorder = ChangeRecorder(),
            hiddenSeed = "seed",
            registry = CommandRegistry(GeneralActionPipeline()),
            startYear = 184,
            turnTerm = 120,
        )

        val result = processor.process(
            generalId = 10,
            officerLevel = 12,
            nationCommand = ChosenCommand(
                "che_발령",
                linkedMapOf("destGeneralID" to 14, "destCityID" to 9),
            ),
            lastTurn = LastTurn(),
            year = 200,
            month = 3,
            date = "00:10",
        )

        val destination = world.getGeneralById(14)!!
        @Suppress("UNCHECKED_CAST")
        val aux = destination.meta["aux"] as Map<String, Any?>
        assertEquals(9, destination.cityId)
        assertEquals(2_403, (aux["last발령"] as Number).toInt())
        assertEquals(496, (aux["max_domestic_critical"] as Number).toInt())
        assertEquals(null, result.term)
        @Suppress("UNCHECKED_CAST")
        val stored = world.getNationById(1)!!.meta["turn_last_12"] as Map<String, Any?>
        assertEquals(listOf("command", "arg"), stored.keys.toList())
    }
}
