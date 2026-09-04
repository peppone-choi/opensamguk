package opensamguk.engine.turn

import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.flush.DeltaGenerationSession
import opensamguk.logic.world.*
import java.time.Instant
import kotlin.test.*

class WaterControlRecorderTest {
    private val hash = "a".repeat(64)
    private fun control(states: List<WaterControlState> = emptyList()) = WaterControlSnapshot("r1", hash, setOf("lake"), states)
    private fun world(snapshot: WaterControlSnapshot? = control(), map: String = "han-world-v3") = InMemoryTurnWorld(
        WorldSnapshot(TurnWorldState(1, 200, 1, 60, Instant.EPOCH, config = mapOf("mapName" to map)),
            worldId = WorldId(1), waterControlSnapshot = snapshot),
    )
    private fun assessment(status: WaterBlockadeState = WaterBlockadeState.OPEN) =
        WaterControlAssessment("r1", hash, "lake", 3L, emptyList(), status)

    @Test fun `multiple same tick changes retain the first persisted revision and final state`() {
        val initial = WaterControlState("r1", hash, "lake", 3L, emptyList(), WaterBlockadeState.OPEN, 4)
        val world = world(control(listOf(initial)))
        val recorder = ChangeRecorder()
        assertIs<WaterControlChangeResult.Changed>(recorder.applyWaterControlAssessment(world, 4, assessment(WaterBlockadeState.BLOCKED)))
        assertIs<WaterControlChangeResult.Changed>(recorder.applyWaterControlAssessment(world, 5, assessment(WaterBlockadeState.CONTESTED)))
        val write = recorder.waterControlWrites().single()
        assertEquals(4L, write.expectedRevision)
        assertEquals(6L, write.state.revision)
        assertEquals(WaterBlockadeState.CONTESTED, world.waterControlSnapshot()!!.stateFor("lake")!!.blockadeState)
        assertTrue(recorder.isDirty)
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertEquals(listOf(write), payload.waterControlWrites)
        recorder.clear()
        assertFalse(recorder.isDirty)
        assertEquals(6L, payload.waterControlWrites.single().state.revision)
        assertFailsWith<UnsupportedOperationException> { (payload.waterControlWrites as MutableList<*>).clear() }
    }

    @Test fun `two assessments before first flush remain insert with absent expected revision`() {
        val world = world()
        val recorder = ChangeRecorder()
        recorder.applyWaterControlAssessment(world, null, assessment())
        recorder.applyWaterControlAssessment(world, 1, assessment(WaterBlockadeState.BLOCKED))
        assertNull(recorder.waterControlWrites().single().expectedRevision)
        assertEquals(2L, recorder.waterControlWrites().single().state.revision)
    }

    @Test fun `stale assessment and no-op have no dirty or memory side effect`() {
        val state = WaterControlState("r1", hash, "lake", 3L, emptyList(), WaterBlockadeState.OPEN, 1)
        val world = world(control(listOf(state)))
        val before = world.waterControlSnapshot()
        val recorder = ChangeRecorder()
        assertIs<WaterControlChangeResult.Unchanged>(recorder.applyWaterControlAssessment(world, 1, assessment()))
        assertIs<WaterControlChangeResult.Denied>(recorder.applyWaterControlAssessment(world, 0, assessment(WaterBlockadeState.BLOCKED)))
        assertFalse(recorder.isDirty)
        assertSame(before, world.waterControlSnapshot())
    }

    @Test fun `prepared generation refuses water mutation before changing memory`() {
        val world = world()
        val generation = DeltaGenerationSession()
        val recorder = ChangeRecorder(generationSession = generation)
        generation.prepare()
        assertFailsWith<IllegalStateException> { recorder.applyWaterControlAssessment(world, null, assessment()) }
        assertNull(world.waterControlSnapshot()!!.stateFor("lake"))
        assertFalse(recorder.isDirty)
    }

    @Test fun `legacy worlds never accept or acquire water control`() {
        for (map in listOf("che", "han", "han-world-v2")) {
            val world = world(null, map)
            val recorder = ChangeRecorder()
            assertEquals(WaterControlDenialCode.UNSUPPORTED_WORLD,
                assertIs<WaterControlChangeResult.Denied>(recorder.applyWaterControlAssessment(world, null, assessment())).code)
            assertNull(world.waterControlSnapshot())
            assertFalse(recorder.isDirty)
            assertFailsWith<IllegalArgumentException> { world(control(), map) }
        }
    }

    @Test fun `payload cannot retag another worlds pending control writes`() {
        val first = world()
        val second = InMemoryTurnWorld(WorldSnapshot(
            TurnWorldState(2, 200, 1, 60, Instant.EPOCH, config = mapOf("mapName" to "han-world-v3")),
            worldId = WorldId(2), waterControlSnapshot = control(),
        ))
        val recorder = ChangeRecorder()
        recorder.applyWaterControlAssessment(first, null, assessment())
        assertFailsWith<IllegalStateException> {
            DatabaseHooks.toFlushPayload(second, recorder, second.consumeDirtyState())
        }
        assertNull(second.waterControlSnapshot()!!.stateFor("lake"))
        assertEquals(1L, recorder.waterControlWrites().single().state.revision)
        assertEquals(WorldId(1), DatabaseHooks.toFlushPayload(first, recorder, first.consumeDirtyState()).worldId)
    }
}
