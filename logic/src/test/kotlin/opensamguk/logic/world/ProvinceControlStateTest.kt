package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProvinceControlStateTest {
    private val hash = "a".repeat(64)

    private fun snapshot(states: List<ProvinceControlState> = emptyList()) =
        ProvinceControlSnapshot("r1", hash, setOf("p1", "p2"), states)

    private fun assessment(
        provinceId: String = "p1",
        nationId: Int = 0,
        topologyRevision: String = "r1",
        topologyHash: String = hash,
    ) = ProvinceControlAssessment(topologyRevision, topologyHash, provinceId, nationId)

    @Test
    fun `missing control stays unknown while explicit neutral control creates revision one`() {
        val before = snapshot()
        assertNull(before.stateFor("p1"))

        val change = assertIs<ProvinceControlChangeResult.Changed>(
            projectProvinceControl(before, null, assessment()),
        )

        assertNull(change.expectedRevision)
        assertEquals(0, change.state.nationId)
        assertEquals(1L, change.state.revision)
        assertNull(before.stateFor("p1"))
    }

    @Test
    fun `matching content is unchanged but stale revision is denied before no-op comparison`() {
        val state = ProvinceControlState("r1", hash, "p1", 4, 7)
        val current = snapshot(listOf(state))

        val unchanged = assertIs<ProvinceControlChangeResult.Unchanged>(
            projectProvinceControl(current, 7, assessment(nationId = 4)),
        )
        assertSame(state, unchanged.state)
        assertEquals(ProvinceControlDenialCode.STALE_REVISION, assertIs<ProvinceControlChangeResult.Denied>(
            projectProvinceControl(current, null, assessment(nationId = 4)),
        ).code)
    }

    @Test
    fun `changed control increments revision and reports optimistic expectation`() {
        val state = ProvinceControlState("r1", hash, "p1", 4, 7)
        val change = assertIs<ProvinceControlChangeResult.Changed>(
            projectProvinceControl(snapshot(listOf(state)), 7, assessment(nationId = 5)),
        )

        assertEquals(7L, change.expectedRevision)
        assertEquals(5, change.state.nationId)
        assertEquals(8L, change.state.revision)
    }

    @Test
    fun `unknown province topology mismatch and exhausted revision fail closed`() {
        assertEquals(ProvinceControlDenialCode.UNKNOWN_PROVINCE, assertIs<ProvinceControlChangeResult.Denied>(
            projectProvinceControl(snapshot(), null, assessment(provinceId = "invented")),
        ).code)
        for (candidate in listOf(
            assessment(topologyRevision = "old"),
            assessment(topologyHash = "b".repeat(64)),
        )) {
            assertEquals(ProvinceControlDenialCode.TOPOLOGY_MISMATCH, assertIs<ProvinceControlChangeResult.Denied>(
                projectProvinceControl(snapshot(), null, candidate),
            ).code)
        }
        val max = ProvinceControlState("r1", hash, "p1", 4, Long.MAX_VALUE)
        assertEquals(ProvinceControlDenialCode.REVISION_EXHAUSTED, assertIs<ProvinceControlChangeResult.Denied>(
            projectProvinceControl(snapshot(listOf(max)), Long.MAX_VALUE, assessment(nationId = 5)),
        ).code)
    }

    @Test
    fun `constructors and restored snapshots reject malformed spatial control`() {
        val state = ProvinceControlState("r1", hash, "p1", 0, 1)
        assertFailsWith<IllegalArgumentException> { ProvinceControlState("", hash, "p1", 0, 1) }
        assertFailsWith<IllegalArgumentException> { ProvinceControlState("r1", "bad", "p1", 0, 1) }
        assertFailsWith<IllegalArgumentException> { ProvinceControlState("r1", hash, "", 0, 1) }
        assertFailsWith<IllegalArgumentException> { ProvinceControlState("r1", hash, "p1", -1, 1) }
        assertFailsWith<IllegalArgumentException> { ProvinceControlState("r1", hash, "p1", 0, 0) }
        assertFailsWith<IllegalArgumentException> { assessment(nationId = -1) }
        assertFailsWith<IllegalArgumentException> { snapshot(listOf(state, state)) }
        assertFailsWith<IllegalArgumentException> { ProvinceControlSnapshot("old", hash, setOf("p1"), listOf(state)) }
        assertFailsWith<IllegalArgumentException> { ProvinceControlSnapshot("r1", hash, setOf("p2"), listOf(state)) }
    }

    @Test
    fun `snapshot defensively copies and exposes immutable collections`() {
        val provinces = mutableSetOf("p1")
        val state = ProvinceControlState("r1", hash, "p1", 0, 1)
        val rows = mutableListOf(state)
        val captured = ProvinceControlSnapshot("r1", hash, provinces, rows)
        provinces.clear()
        rows.clear()

        assertEquals(setOf("p1"), captured.knownProvinceIds)
        assertEquals(state, captured.stateFor("p1"))
        assertFailsWith<UnsupportedOperationException> {
            (captured.knownProvinceIds as MutableSet<String>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (captured.statesByProvinceId as MutableMap<String, ProvinceControlState>).clear()
        }
        val replaced = captured.withState(state.copy(nationId = 2, revision = 2))
        assertEquals(0, captured.stateFor("p1")?.nationId)
        assertEquals(2, replaced.stateFor("p1")?.nationId)
    }

    @Test
    fun `from topology derives only declared land province ids`() {
        val topology = StrategicTopologySnapshot(
            "topology-v1", setOf("p1", "p2"), emptyList(), emptyList(), emptyList(), mapOf("map" to "sha"),
        )
        val captured = ProvinceControlSnapshot.fromTopology(topology)

        assertEquals("topology-v1", captured.topologyRevision)
        assertEquals(topology.contentHash, captured.topologyHash)
        assertEquals(setOf("p1", "p2"), captured.knownProvinceIds)
        assertNull(captured.stateFor("p1"))
    }
}
