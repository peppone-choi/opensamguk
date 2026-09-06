package opensamguk.engine.turn

import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.flush.DeltaGenerationSession
import opensamguk.logic.world.*
import java.time.Instant
import kotlin.test.*

class SpatialStateRecorderTest {
    private val hash = "a".repeat(64)
    private val land = StrategicNodeRef.LandProvince("p1")
    private val water = StrategicNodeRef.WaterZone("lake")
    private fun general(id: Int) = TurnGeneral(id = id, name = "G", nationId = 0, cityId = 0,
        troopId = 0, stats = GeneralStats(50, 50, 50), experience = 0, dedication = 0,
        officerLevel = 0, turnTime = Instant.EPOCH)
    private fun snapshot(id: Int = 1) = WorldSnapshot(
        TurnWorldState(id, 200, 1, 60, Instant.EPOCH, config = mapOf("mapName" to "han-world-v3")),
        worldId = WorldId(id), generals = listOf(general(7)),
        provinceControlSnapshot = ProvinceControlSnapshot("r1", hash, setOf("p1")),
        generalPositionSnapshot = GeneralPositionSnapshot("r1", hash, setOf("p1"), setOf("lake")),
        waterControlSnapshot = WaterControlSnapshot("r1", hash, setOf("lake")),
    )
    private fun province(nation: Int) = ProvinceControlAssessment("r1", hash, "p1", nation)
    private fun position(node: StrategicNodeRef, id: Int = 7) = GeneralPositionAssessment("r1", hash, id, node)

    @Test fun `coalescing retains persisted revision and immutable retry payloads`() {
        val base = snapshot()
        val world = InMemoryTurnWorld(base.copy(
            provinceControlSnapshot = base.provinceControlSnapshot!!.withState(ProvinceControlState("r1", hash, "p1", 3, 3)),
            generalPositionSnapshot = base.generalPositionSnapshot!!.withState(GeneralPositionState("r1", hash, 7, land, 3)),
        ))
        val recorder = ChangeRecorder()
        recorder.applyProvinceControlAssessment(world, 3, province(4))
        recorder.applyGeneralPositionAssessment(world, 3, position(water))
        val previous = world.generalPositionSnapshot()!!
        val retry = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        recorder.applyProvinceControlAssessment(world, 4, province(5))
        recorder.applyGeneralPositionAssessment(world, 4, position(land))
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertEquals(3L, payload.provinceControlWrites.single().expectedRevision)
        assertEquals(3L, payload.generalPositionWrites.single().expectedRevision)
        assertEquals(5L, payload.provinceControlWrites.single().state.revision)
        assertEquals(5L, payload.generalPositionWrites.single().state.revision)
        assertEquals(water, previous.stateFor(7)!!.node)
        assertEquals(4L, retry.generalPositionWrites.single().state.revision)
        assertTrue(recorder.isDirty)
        recorder.clear()
        assertFalse(recorder.isDirty)
        assertEquals(5L, payload.generalPositionWrites.single().state.revision)
        assertFailsWith<UnsupportedOperationException> { (payload.generalPositionWrites as MutableList<*>).clear() }
    }

    @Test fun `new rows remain inserts across repeated changes and noops stay clean`() {
        val world = InMemoryTurnWorld(snapshot())
        val recorder = ChangeRecorder()
        recorder.applyProvinceControlAssessment(world, null, province(0))
        recorder.applyGeneralPositionAssessment(world, null, position(land))
        recorder.applyProvinceControlAssessment(world, 1, province(2))
        recorder.applyGeneralPositionAssessment(world, 1, position(water))
        assertNull(recorder.provinceControlWrites().single().expectedRevision)
        assertNull(recorder.generalPositionWrites().single().expectedRevision)
        recorder.clear()
        assertIs<ProvinceControlChangeResult.Unchanged>(recorder.applyProvinceControlAssessment(world, 2, province(2)))
        assertIs<GeneralPositionChangeResult.Unchanged>(recorder.applyGeneralPositionAssessment(world, 2, position(water)))
        assertIs<ProvinceControlChangeResult.Denied>(recorder.applyProvinceControlAssessment(world, 1, province(3)))
        assertIs<GeneralPositionChangeResult.Denied>(recorder.applyGeneralPositionAssessment(world, 1, position(land)))
        assertFalse(recorder.isDirty)
    }

    @Test fun `prepared generation refuses spatial and lifecycle mutations before memory changes`() {
        val world = InMemoryTurnWorld(snapshot())
        val session = DeltaGenerationSession()
        val recorder = ChangeRecorder(generationSession = session)
        session.prepare()
        assertFailsWith<IllegalStateException> { recorder.applyProvinceControlAssessment(world, null, province(1)) }
        assertFailsWith<IllegalStateException> { recorder.applyGeneralPositionAssessment(world, null, position(land)) }
        assertFailsWith<IllegalStateException> { recorder.markGeneralDeleted(world, 7) }
        assertFailsWith<IllegalStateException> { recorder.recordGeneralCreate(world, general(8)) }
        assertNull(world.provinceControlSnapshot()!!.stateFor("p1"))
        assertNull(world.generalPositionSnapshot()!!.stateFor(7))
        assertNotNull(world.getGeneralById(7))
        assertNull(world.getGeneralById(8))
        assertFalse(recorder.isDirty)
        assertTrue(recorder.generalPositionWrites().isEmpty())
        assertTrue(recorder.provinceControlWrites().isEmpty())
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.createdGenerals.isEmpty())
        assertTrue(dirty.deletedGenerals.isEmpty())
    }

    @Test fun `unknown or deleted general cannot acquire position and create delete cancels pending insert`() {
        val world = InMemoryTurnWorld(snapshot())
        val recorder = ChangeRecorder()
        assertEquals(GeneralPositionDenialCode.UNKNOWN_GENERAL,
            assertIs<GeneralPositionChangeResult.Denied>(recorder.applyGeneralPositionAssessment(world, null, position(land, 8))).code)
        recorder.recordGeneralCreate(world, general(8))
        recorder.applyGeneralPositionAssessment(world, null, position(land, 8))
        assertTrue(recorder.markGeneralDeleted(world, 8))
        assertNull(world.generalPositionSnapshot()!!.stateFor(8))
        assertTrue(recorder.generalPositionWrites().isEmpty())
        assertEquals(GeneralPositionDenialCode.UNKNOWN_GENERAL,
            assertIs<GeneralPositionChangeResult.Denied>(recorder.applyGeneralPositionAssessment(world, null, position(water, 8))).code)
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.createdGenerals.isEmpty())
        assertTrue(dirty.deletedGenerals.isEmpty())
    }

    @Test fun `recorder cannot mix worlds even across different spatial channels`() {
        val first = InMemoryTurnWorld(snapshot())
        val second = InMemoryTurnWorld(snapshot(2))
        val recorder = ChangeRecorder()
        recorder.applyProvinceControlAssessment(first, null, province(1))
        assertFailsWith<IllegalStateException> { recorder.applyGeneralPositionAssessment(second, null, position(land)) }
        assertFailsWith<IllegalStateException> { recorder.applyWaterControlAssessment(second, null,
            WaterControlAssessment("r1", hash, "lake", null, emptyList(), WaterBlockadeState.OPEN)) }
        assertFailsWith<IllegalStateException> { DatabaseHooks.toFlushPayload(second, recorder, second.consumeDirtyState()) }
        assertNull(second.generalPositionSnapshot()!!.stateFor(7))
    }

    @Test fun `never persisted creation cancels archive owner access and position writes`() {
        val world = InMemoryTurnWorld(snapshot())
        val recorder = ChangeRecorder()
        recorder.recordGeneralCreate(world, general(8))
        recorder.applyGeneralPositionAssessment(world, null, position(land, 8))
        recorder.recordAccessLogUpsert(world, GeneralAccessLog(8, refresh = 3))
        recorder.recordGeneralOwnerDelete(8)
        recorder.markGeneralDeleted(world, 8)
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertTrue(payload.oldGeneralSnapshots.isEmpty(), "An unpersisted general must not acquire an archive")
        assertTrue(payload.generalOwnerDeletes.isEmpty())
        assertTrue(payload.generalAccessLogDeletes.isEmpty())
        assertTrue(payload.generalAccessLogUpserts.isEmpty())
        assertTrue(payload.generalPositionWrites.isEmpty())
        assertTrue(payload.createdGenerals.isEmpty())
        assertTrue(payload.deletedGenerals.isEmpty())
        assertTrue(world.listAccessLogs().none { it.generalId == 8 })
        assertEquals(GeneralPositionDenialCode.UNKNOWN_GENERAL,
            assertIs<GeneralPositionChangeResult.Denied>(recorder.applyGeneralPositionAssessment(world, null, position(land, 8))).code)
    }

    @Test fun `lifecycle first binds recorder before another worlds spatial assessments`() {
        for (create in listOf(false, true)) {
            val first = InMemoryTurnWorld(snapshot())
            val second = InMemoryTurnWorld(snapshot(2))
            val recorder = ChangeRecorder()
            if (create) recorder.recordGeneralCreate(first, general(8)) else recorder.markGeneralDeleted(first, 7)
            assertFailsWith<IllegalStateException> { recorder.applyProvinceControlAssessment(second, null, province(1)) }
            assertFailsWith<IllegalStateException> { recorder.applyGeneralPositionAssessment(second, null, position(land)) }
            assertFailsWith<IllegalStateException> { recorder.applyWaterControlAssessment(second, null,
                WaterControlAssessment("r1", hash, "lake", null, emptyList(), WaterBlockadeState.OPEN)) }
            assertNull(second.provinceControlSnapshot()!!.stateFor("p1"))
            assertNull(second.generalPositionSnapshot()!!.stateFor(7))
        }
    }

    @Test fun `lifecycle first rejects wrong world payload and subsequent lifecycle effects`() {
        for (create in listOf(false, true)) {
            val first = InMemoryTurnWorld(snapshot())
            val second = InMemoryTurnWorld(snapshot(2))
            val recorder = ChangeRecorder()
            if (create) recorder.recordGeneralCreate(first, general(8)) else recorder.markGeneralDeleted(first, 7)
            assertFailsWith<IllegalStateException> { DatabaseHooks.toFlushPayload(second, recorder, second.consumeDirtyState()) }
            assertFailsWith<IllegalStateException> { recorder.recordGeneralCreate(second, general(8)) }
            assertFailsWith<IllegalStateException> { recorder.markGeneralDeleted(second, 7) }
            assertNotNull(second.getGeneralById(7))
            assertNull(second.getGeneralById(8))
            val payload = DatabaseHooks.toFlushPayload(first, recorder, first.consumeDirtyState())
            assertEquals(WorldId(1), payload.worldId)
            assertEquals(if (create) emptyList() else listOf(7), payload.generalOwnerDeletes)
        }
    }

    @Test fun `constructor rejects unsupported maps pin and catalog disagreement and orphan positions`() {
        val base = snapshot()
        assertFailsWith<IllegalArgumentException> { base.copy(state = base.state.copy(config = mapOf("mapName" to "han-world-v2"))) }
        assertFailsWith<IllegalArgumentException> { base.copy(provinceControlSnapshot = ProvinceControlSnapshot("r2", hash, setOf("p1"))) }
        assertFailsWith<IllegalArgumentException> { base.copy(provinceControlSnapshot = ProvinceControlSnapshot("r1", hash, setOf("p2"))) }
        assertFailsWith<IllegalArgumentException> { base.copy(waterControlSnapshot = WaterControlSnapshot("r1", hash, setOf("coast"))) }
        assertFailsWith<IllegalArgumentException> { base.copy(generalPositionSnapshot = base.generalPositionSnapshot!!.withState(
            GeneralPositionState("r1", hash, 99, land, 1))) }
    }
}
