package opensamguk.engine.world

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.ProvideNPCTroopLeaderAction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldActionContextNpcTroopLeaderTest {

    @Test
    fun `monthly action mints troop leader troop and thirty gather turns`() {
        val state = TurnWorldState(
            id = 9,
            currentYear = 200,
            currentMonth = 4,
            tickSeconds = 3600,
            lastTurnTime = Instant.parse("0200-04-01T00:00:00Z"),
            meta = mapOf(
                "hiddenSeed" to "00000000000000000000000000000000",
                "startYear" to 200,
                "turnterm" to 1,
            ),
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state,
                nations = listOf(Nation(id = 7, name = "촉", color = "#ff0000", level = 2)),
                cities = listOf(City(id = 1, name = "성도", nationId = 7, level = 5)),
                worldId = WorldId(9),
            ),
        )
        val recorder = ChangeRecorder()
        val context = WorldActionContext(
            env = mutableMapOf("year" to 200, "month" to 4),
            world = world,
            recorder = recorder,
            pipeline = GeneralActionPipeline(),
        )

        ProvideNPCTroopLeaderAction().run(context)

        val dirty = world.consumeDirtyState()
        val createdGeneral = dirty.createdGenerals.single()
        assertEquals(5, createdGeneral.npcState)
        assertEquals(createdGeneral.id, createdGeneral.troopId)
        assertEquals("㉥부대장   1", createdGeneral.name)
        assertEquals(30, createdGeneral.initialTurns.size)
        assertEquals(setOf("che_집합"), createdGeneral.initialTurns.map { it.actionCode }.toSet())
        assertEquals(createdGeneral.id, dirty.createdTroops.single().id)
        assertEquals(
            1,
            recorder.kvDirty()[KvKey("game_env", "game_env", "lastNPCTroopLeaderID")],
        )
    }
}
