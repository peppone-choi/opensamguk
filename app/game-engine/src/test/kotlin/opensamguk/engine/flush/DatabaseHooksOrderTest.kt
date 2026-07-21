package opensamguk.engine.flush

import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseHooksOrderTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(id: Int, nationId: Int, gold: Int = 100) = TurnGeneral(
        id = id, name = "g$id", nationId = nationId, cityId = 5, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0, officerLevel = 0,
        gold = gold, turnTime = t0,
    )

    private fun city(id: Int) = City(id = id, name = "c$id", nationId = 2, level = 5)
    private fun nation(id: Int) = Nation(id = id, name = "n$id", color = "#000")
    private fun diplomacy(from: Int, to: Int) = TurnDiplomacy(from, to, state = 7, term = 0)

    @Test
    fun `mixed dirty set records the exact flush op-tag sequence`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 1, 3600, t0),
                generals = listOf(general(10, nationId = 2)),
                cities = listOf(city(5)),
                nations = listOf(nation(1), nation(2)),
                diplomacy = listOf(diplomacy(1, 2), diplomacy(2, 1)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(1, 200, 1, 3600, t0)).id),
            ),
        )
        world.updateGeneral(general(10, nationId = 2, gold = 250))
        world.updateCity(city(5))
        world.removeNation(1) // cascades diplomacy (1,2) and (2,1)
        world.pushLog(LogEntryDraft(scope = "GENERAL", category = "ACTION", text = "acted"))

        val recorder = FlushOpRecorder()
        DatabaseHooks.flushChanges(world, recorder)

        val expected = listOf(
            FlushOp("world_state", FlushOp.Verb.UPDATE, 1),
            FlushOp("diplomacy", FlushOp.Verb.DELETE_MANY, 1),
            FlushOp("nation_turn", FlushOp.Verb.DELETE_MANY, 1),
            FlushOp("nation", FlushOp.Verb.DELETE_MANY, 1),
            FlushOp("general", FlushOp.Verb.UPDATE, 1),
            FlushOp("city", FlushOp.Verb.UPDATE, 1),
            FlushOp("rank_data", FlushOp.Verb.UPSERT, 37),
            FlushOp("log_entry", FlushOp.Verb.CREATE_MANY, 1),
            FlushOp("reserved_turns", FlushOp.Verb.UPDATE, 1),
        )
        assertEquals(expected, recorder.ops())
    }

    @Test
    fun `rank_data upsert count is 37 per updated general`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 1, 3600, t0),
                generals = listOf(general(10, nationId = 1), general(11, nationId = 1)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(1, 200, 1, 3600, t0)).id),
            ),
        )
        world.updateGeneral(general(10, nationId = 1, gold = 1))
        world.updateGeneral(general(11, nationId = 1, gold = 2))

        val recorder = FlushOpRecorder()
        DatabaseHooks.flushChanges(world, recorder)

        val rank = recorder.ops().single { it.table == "rank_data" && it.verb == FlushOp.Verb.UPSERT }
        assertEquals(2 * DatabaseHooks.RANK_ROWS_PER_GENERAL, rank.count)
        assertEquals(74, rank.count)
    }

    @Test
    fun `created-then-deleted general in same tick produces neither create nor delete op`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = TurnWorldState(1, 200, 1, 3600, t0), worldId = opensamguk.common.world.WorldId((TurnWorldState(1, 200, 1, 3600, t0)).id)))
        world.createGeneral(general(99, nationId = 1))
        world.removeGeneral(99)

        val recorder = FlushOpRecorder()
        DatabaseHooks.flushChanges(world, recorder)

        val ops = recorder.ops()
        assertEquals(
            listOf(
                FlushOp("world_state", FlushOp.Verb.UPDATE, 1),
                FlushOp("reserved_turns", FlushOp.Verb.UPDATE, 1),
            ),
            ops,
            "only the always-on world_state + reservedTurns ops survive a cancelled create/delete",
        )
    }
}
