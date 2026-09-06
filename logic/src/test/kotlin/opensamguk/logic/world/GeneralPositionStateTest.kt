package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class GeneralPositionStateTest {
    private val hash = "a".repeat(64)
    private val land = StrategicNodeRef.LandProvince("p1")
    private val water = StrategicNodeRef.WaterZone("lake")

    private fun snapshot(states: List<GeneralPositionState> = emptyList()) =
        GeneralPositionSnapshot("r1", hash, setOf("p1", "p2"), setOf("lake"), states)

    private fun assessment(
        generalId: Int = 1,
        node: StrategicNodeRef = land,
        topologyRevision: String = "r1",
        topologyHash: String = hash,
    ) = GeneralPositionAssessment(topologyRevision, topologyHash, generalId, node)

    @Test
    fun `missing position stays unknown and first explicit land position creates revision one`() {
        val before = snapshot()
        assertNull(before.stateFor(1))

        val change = assertIs<GeneralPositionChangeResult.Changed>(
            projectGeneralPosition(before, null, assessment()),
        )

        assertNull(change.expectedRevision)
        assertEquals(land, change.state.node)
        assertEquals(1L, change.state.revision)
        assertNull(before.stateFor(1))
    }

    @Test
    fun `matching position is unchanged but stale revision is denied before no-op comparison`() {
        val state = GeneralPositionState("r1", hash, 1, water, 7)
        val current = snapshot(listOf(state))

        val unchanged = assertIs<GeneralPositionChangeResult.Unchanged>(
            projectGeneralPosition(current, 7, assessment(node = water)),
        )
        assertSame(state, unchanged.state)
        assertEquals(GeneralPositionDenialCode.STALE_REVISION, assertIs<GeneralPositionChangeResult.Denied>(
            projectGeneralPosition(current, null, assessment(node = water)),
        ).code)
    }

    @Test
    fun `movement between land and water increments revision and reports expectation`() {
        val state = GeneralPositionState("r1", hash, 1, land, 7)
        val change = assertIs<GeneralPositionChangeResult.Changed>(
            projectGeneralPosition(snapshot(listOf(state)), 7, assessment(node = water)),
        )

        assertEquals(7L, change.expectedRevision)
        assertEquals(water, change.state.node)
        assertEquals(8L, change.state.revision)
    }

    @Test
    fun `unknown node topology mismatch and exhausted revision fail closed`() {
        for (node in listOf(StrategicNodeRef.LandProvince("invented"), StrategicNodeRef.WaterZone("sea"))) {
            assertEquals(GeneralPositionDenialCode.UNKNOWN_NODE, assertIs<GeneralPositionChangeResult.Denied>(
                projectGeneralPosition(snapshot(), null, assessment(node = node)),
            ).code)
        }
        for (candidate in listOf(
            assessment(topologyRevision = "old"),
            assessment(topologyHash = "b".repeat(64)),
        )) {
            assertEquals(GeneralPositionDenialCode.TOPOLOGY_MISMATCH, assertIs<GeneralPositionChangeResult.Denied>(
                projectGeneralPosition(snapshot(), null, candidate),
            ).code)
        }
        val max = GeneralPositionState("r1", hash, 1, land, Long.MAX_VALUE)
        assertEquals(GeneralPositionDenialCode.REVISION_EXHAUSTED, assertIs<GeneralPositionChangeResult.Denied>(
            projectGeneralPosition(snapshot(listOf(max)), Long.MAX_VALUE, assessment(node = water)),
        ).code)
    }

    @Test
    fun `constructors and restored snapshots reject malformed spatial positions`() {
        val state = GeneralPositionState("r1", hash, 1, land, 1)
        assertFailsWith<IllegalArgumentException> { GeneralPositionState("", hash, 1, land, 1) }
        assertFailsWith<IllegalArgumentException> { GeneralPositionState("r1", "bad", 1, land, 1) }
        assertFailsWith<IllegalArgumentException> { GeneralPositionState("r1", hash, 0, land, 1) }
        assertFailsWith<IllegalArgumentException> { GeneralPositionState("r1", hash, 1, land, 0) }
        assertFailsWith<IllegalArgumentException> { assessment(generalId = -1) }
        assertFailsWith<IllegalArgumentException> { snapshot(listOf(state, state)) }
        assertFailsWith<IllegalArgumentException> { GeneralPositionSnapshot("old", hash, setOf("p1"), setOf("lake"), listOf(state)) }
        assertFailsWith<IllegalArgumentException> { GeneralPositionSnapshot("r1", hash, setOf("p2"), setOf("lake"), listOf(state)) }
        val waterState = state.copy(node = water)
        assertFailsWith<IllegalArgumentException> { GeneralPositionSnapshot("r1", hash, setOf("p1"), emptySet(), listOf(waterState)) }
    }

    @Test
    fun `snapshot copies collections and with-state and without-general do not mutate it`() {
        val provinces = mutableSetOf("p1")
        val zones = mutableSetOf("lake")
        val state = GeneralPositionState("r1", hash, 1, land, 1)
        val rows = mutableListOf(state)
        val captured = GeneralPositionSnapshot("r1", hash, provinces, zones, rows)
        provinces.clear()
        zones.clear()
        rows.clear()

        assertEquals(setOf("p1"), captured.knownLandProvinceIds)
        assertEquals(setOf("lake"), captured.knownWaterZoneIds)
        assertEquals(state, captured.stateFor(1))
        assertFailsWith<UnsupportedOperationException> {
            (captured.knownLandProvinceIds as MutableSet<String>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (captured.knownWaterZoneIds as MutableSet<String>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (captured.statesByGeneralId as MutableMap<Int, GeneralPositionState>).clear()
        }

        val replaced = captured.withState(state.copy(node = water, revision = 2))
        val removed = replaced.withoutGeneral(1)
        assertEquals(land, captured.stateFor(1)?.node)
        assertEquals(water, replaced.stateFor(1)?.node)
        assertNull(removed.stateFor(1))
    }

    @Test
    fun `from topology derives declared land and water node ids`() {
        val zone = WaterZoneRecord(
            "lake", WaterZoneKind.LAKE_BASIN, "geometry", listOf("source"), EvidenceConfidence.EXACT,
            seasonalAvailability = SeasonalAvailability.ALWAYS,
        )
        val topology = StrategicTopologySnapshot(
            "topology-v1", setOf("p1"), listOf(zone), emptyList(), emptyList(), mapOf("map" to "sha"),
        )
        val captured = GeneralPositionSnapshot.fromTopology(topology)

        assertEquals("topology-v1", captured.topologyRevision)
        assertEquals(topology.contentHash, captured.topologyHash)
        assertEquals(setOf("p1"), captured.knownLandProvinceIds)
        assertEquals(setOf("lake"), captured.knownWaterZoneIds)
        assertNull(captured.stateFor(1))
    }
}
