package opensamguk.engine.turn

import opensamguk.common.world.WorldId
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.imperial.ImperialHouse
import opensamguk.logic.imperial.ImperialLineStatus
import opensamguk.logic.imperial.ImperialTransitionType
import opensamguk.logic.imperial.ImperialWorldCodec
import opensamguk.logic.imperial.ImperialWorldState
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImperialDeathHookTest {
    private val time = Instant.parse("0200-01-01T00:00:00Z")
    private val env = LifecycleEnv(12, 200, 1, 60)

    private fun general(id: Int, officerLevel: Int) = TurnGeneral(
        id = id, name = "g$id", nationId = 1, cityId = 5, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
        officerLevel = officerLevel, npcState = 2, turnTime = time,
    )

    private fun world(imperial: Any?, seeded: Boolean = true): InMemoryTurnWorld {
        val state = TurnWorldState(1, 200, 1, 3600, time,
            meta = if (seeded) mapOf(ImperialWorldCodec.META_KEY to imperial) else emptyMap())
        return InMemoryTurnWorld(WorldSnapshot(state = state,
            generals = listOf(general(1, 12), general(2, 1)),
            nations = listOf(Nation(1, "n1", "#000", meta = mapOf("gennum" to 2))),
            worldId = WorldId(1)))
    }

    private fun handler(world: InMemoryTurnWorld, nextRuler: (Int, LifecycleEnv) -> Unit) =
        ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()), "0".repeat(32), 184,
            nextRuler = nextRuler,
            onGeneralDeath = { id, lifecycle -> ImperialDeathHook.apply(world, id, lifecycle) })

    @Test
    fun `imperial successor is committed in memory before national ruler hook and tombstone`() {
        val imperial = ImperialWorldState(listOf(ImperialHouse("test_line", "시험 계통",
            ImperialLineStatus.ACTIVE, 1, 2, listOf(2), null, 1, 5, 50)),
            emptyList(), emptyList())
        val world = world(ImperialWorldCodec.write(imperial))
        var rulerHookCalled = false
        val handler = handler(world) { _, _ ->
            rulerHookCalled = true
            assertNotNull(world.getGeneralById(1), "national hook runs before tombstone")
            assertEquals(2, ImperialWorldCodec.read(world.getState().meta)!!.houses.single().holderGeneralId)
        }

        handler.killOrReleasePossession(assertNotNull(world.getGeneralById(1)), env)

        assertTrue(rulerHookCalled)
        assertNull(world.getGeneralById(1))
        assertEquals(setOf(1), handler.recorder.deletedGeneralIds())
        val transition = ImperialWorldCodec.read(world.getState().meta)!!.transitions.single()
        assertEquals(ImperialTransitionType.DEATH_SUCCESSION, transition.type)
        assertEquals(2, transition.toHolderGeneralId)
    }

    @Test
    fun `invalid imperial state stops national succession and tombstone`() {
        val world = world(mapOf("broken" to true))
        var rulerHookCalled = false
        val handler = handler(world) { _, _ -> rulerHookCalled = true }

        assertFailsWith<IllegalArgumentException> {
            handler.killOrReleasePossession(assertNotNull(world.getGeneralById(1)), env)
        }

        assertEquals(false, rulerHookCalled)
        assertNotNull(world.getGeneralById(1))
        assertTrue(handler.recorder.deletedGeneralIds().isEmpty())
    }

    @Test
    fun `unseeded world keeps the existing national death path`() {
        val world = world(null, seeded = false)
        var rulerHookCalled = false
        val handler = handler(world) { _, _ -> rulerHookCalled = true }

        handler.killOrReleasePossession(assertNotNull(world.getGeneralById(1)), env)

        assertTrue(rulerHookCalled)
        assertNull(world.getGeneralById(1))
        assertEquals(false, ImperialWorldCodec.META_KEY in world.getState().meta)
    }
}
