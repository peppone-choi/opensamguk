package opensamguk.engine.world

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.event.EventStore
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.BlockScoutAction
import opensamguk.logic.world.BlockScoutWorld
import opensamguk.logic.world.UnblockScoutAction
import opensamguk.logic.world.UnblockScoutWorldView
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldScoutActionContextTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun world(firstScout: Int = 0, secondScout: Int = 1): InMemoryTurnWorld =
        InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = t0,
                ),
                nations = listOf(
                    Nation(id = 1, name = "촉", color = "#f00", level = 1, meta = linkedMapOf("scout" to firstScout)),
                    Nation(id = 2, name = "위", color = "#00f", level = 1, meta = linkedMapOf("scout" to secondScout)),
                ),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = t0,
                )).id),
            ),
        )

    @Test
    fun `factory threads BlockScout and UnblockScout env worlds to the live WorldActionContext`() {
        val env = mutableMapOf<String, Any?>()

        val ctx = WorldEventContextFactory.create(
            world = world(),
            recorder = ChangeRecorder(),
            pipeline = GeneralActionPipeline(),
            hiddenSeed = "hidden",
            startYear = 200,
            mapName = "che",
            eventStore = EventStore(),
        )(env)

        assertIs<BlockScoutWorld>(ctx)
        assertIs<UnblockScoutWorldView>(ctx)
        assertTrue(env[BlockScoutAction.ENV_WORLD] === ctx)
        assertTrue(env[UnblockScoutAction.ENV_WORLD] === ctx)
    }

    @Test
    fun `BlockScoutAction updates every nation scout meta and records block_change_scout game-env KV`() {
        val world = world(firstScout = 0, secondScout = 0)
        val recorder = ChangeRecorder()
        val env = mutableMapOf<String, Any?>()
        WorldEventContextFactory.create(
            world = world,
            recorder = recorder,
            pipeline = GeneralActionPipeline(),
            hiddenSeed = "hidden",
            startYear = 200,
            mapName = "che",
            eventStore = EventStore(),
        )(env)

        BlockScoutAction(blockChangeScout = true).run(checkNotNull(env[BlockScoutAction.ENV_WORLD]) as WorldActionContext)

        assertEquals(listOf(1, 1), world.listNations().sortedBy { it.id }.map { it.meta["scout"] })
        assertEquals(
            mapOf(1 to 1, 2 to 1),
            recorder.nationPatches().associate { it.id to it.meta["scout"] },
        )
        assertEquals(true, recorder.kvDirty()[KvKey("game_env", "game_env", "block_change_scout")])
    }

    @Test
    fun `UnblockScoutAction updates every nation scout meta without touching KV when constructor arg is null`() {
        val world = world(firstScout = 1, secondScout = 1)
        val recorder = ChangeRecorder()
        val env = mutableMapOf<String, Any?>()
        WorldEventContextFactory.create(
            world = world,
            recorder = recorder,
            pipeline = GeneralActionPipeline(),
            hiddenSeed = "hidden",
            startYear = 200,
            mapName = "che",
            eventStore = EventStore(),
        )(env)

        UnblockScoutAction().run(checkNotNull(env[UnblockScoutAction.ENV_WORLD]) as WorldActionContext)

        assertEquals(listOf(0, 0), world.listNations().sortedBy { it.id }.map { it.meta["scout"] })
        assertEquals(
            mapOf(1 to 0, 2 to 0),
            recorder.nationPatches().associate { it.id to it.meta["scout"] },
        )
        assertNull(recorder.kvDirty()[KvKey("game_env", "game_env", "block_change_scout")])
    }
}
