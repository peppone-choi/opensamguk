package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class WaterControlStateTest {
    private val hash = "a".repeat(64)
    private fun snapshot(states: List<WaterControlState> = emptyList()) =
        WaterControlSnapshot("r1", hash, setOf("lake", "coast"), states)
    private fun assessment(
        zone: String = "lake", status: WaterBlockadeState = WaterBlockadeState.OPEN,
        controller: Long? = 3L, contesters: List<Long> = emptyList(), revision: String = "r1",
        topologyHash: String = hash,
    ) = WaterControlAssessment(revision, topologyHash, zone, controller, contesters, status)

    @Test
    fun `absence stays unknown and first explicit assessment creates revision one`() {
        val before = snapshot()
        assertNull(before.stateFor("lake"))
        val change = assertIs<WaterControlChangeResult.Changed>(projectWaterControl(before, null, assessment()))
        assertNull(change.expectedRevision)
        assertEquals(1L, change.state.revision)
        assertEquals(3L, change.state.controllingNationId)
        assertEquals(WaterBlockadeState.OPEN, change.state.blockadeState)
        assertNull(before.stateFor("lake"))
    }

    @Test
    fun `assessment only normalizes explicit contests without deriving a controller`() {
        val input = mutableListOf(7L, 2L, 7L)
        val assessed = assessment(status = WaterBlockadeState.CONTESTED, controller = null, contesters = input)
        input.clear()
        val state = assertIs<WaterControlChangeResult.Changed>(projectWaterControl(snapshot(), null, assessed)).state
        assertEquals(listOf(2L, 7L), state.contestingNationIds)
        assertNull(state.controllingNationId)
        assertFailsWith<UnsupportedOperationException> { (state.contestingNationIds as MutableList<Long>).clear() }
    }

    @Test
    fun `same content is a no-op but stale expected revision is rejected even for a no-op`() {
        val first = assertIs<WaterControlChangeResult.Changed>(projectWaterControl(snapshot(), null, assessment())).state
        val current = snapshot(listOf(first))
        assertIs<WaterControlChangeResult.Unchanged>(projectWaterControl(current, 1L, assessment()))
        assertEquals(WaterControlDenialCode.STALE_REVISION, assertIs<WaterControlChangeResult.Denied>(
            projectWaterControl(current, null, assessment()),
        ).code)
        val blocked = assertIs<WaterControlChangeResult.Changed>(
            projectWaterControl(current, 1L, assessment(status = WaterBlockadeState.BLOCKED)),
        )
        assertEquals(1L, blocked.expectedRevision)
        assertEquals(2L, blocked.state.revision)
    }

    @Test
    fun `unknown zone mismatched topology and revision overflow fail closed`() {
        assertEquals(WaterControlDenialCode.UNKNOWN_ZONE, assertIs<WaterControlChangeResult.Denied>(
            projectWaterControl(snapshot(), null, assessment(zone = "invented")),
        ).code)
        for (a in listOf(assessment(revision = "old"), assessment(topologyHash = "b".repeat(64)))) {
            assertEquals(WaterControlDenialCode.TOPOLOGY_MISMATCH, assertIs<WaterControlChangeResult.Denied>(
                projectWaterControl(snapshot(), null, a),
            ).code)
        }
        val max = WaterControlState("r1", hash, "lake", 3L, emptyList(), WaterBlockadeState.OPEN, Long.MAX_VALUE)
        assertEquals(WaterControlDenialCode.REVISION_EXHAUSTED, assertIs<WaterControlChangeResult.Denied>(
            projectWaterControl(snapshot(listOf(max)), Long.MAX_VALUE, assessment(status = WaterBlockadeState.BLOCKED)),
        ).code)
    }

    @Test
    fun `restored snapshot rejects unknown zone stale pins duplicate rows and malformed state`() {
        val state = WaterControlState("r1", hash, "lake", null, emptyList(), WaterBlockadeState.BLOCKED, 1)
        assertFailsWith<IllegalArgumentException> { snapshot(listOf(state, state)) }
        assertFailsWith<IllegalArgumentException> { WaterControlSnapshot("old", hash, setOf("lake"), listOf(state)) }
        assertFailsWith<IllegalArgumentException> { WaterControlSnapshot("r1", hash, setOf("other"), listOf(state)) }
        assertFailsWith<IllegalArgumentException> { WaterControlState("r1", hash, "lake", null, listOf(3, 2), WaterBlockadeState.CONTESTED, 1) }
        assertFailsWith<IllegalArgumentException> { WaterControlState("r1", hash, "lake", 0, emptyList(), WaterBlockadeState.OPEN, 1) }
        assertFailsWith<IllegalArgumentException> { WaterControlState("r1", hash, "lake", null, emptyList(), WaterBlockadeState.OPEN, 0) }
        assertFailsWith<IllegalArgumentException> { assessment(contesters = listOf(-1)) }
    }

    @Test
    fun `snapshot defensively copies rows and zone collection`() {
        val zones = mutableSetOf("lake")
        val state = WaterControlState("r1", hash, "lake", null, emptyList(), WaterBlockadeState.BLOCKED, 1)
        val rows = mutableListOf(state)
        val snapshot = WaterControlSnapshot("r1", hash, zones, rows)
        zones.clear()
        rows.clear()
        assertEquals(state, snapshot.stateFor("lake"))
        assertEquals(setOf("lake"), snapshot.knownWaterZoneIds)
        assertFailsWith<UnsupportedOperationException> { (snapshot.statesByZoneId as MutableMap<String, WaterControlState>).clear() }
    }
}
