package opensamguk.logic.world

import kotlin.test.*

class LandMarchMetricSnapshotTest {
    private val hash = "a".repeat(64)
    private fun topology() = StrategicTopologySnapshot("test", setOf("A", "B", "C"), emptyList(),
        projectHanDryLandEdges(listOf("A", "B", "C"), intArrayOf(0, 1, 2), listOf("111"), setOf('1'), emptyList(), hash),
        emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to hash))
    private fun metrics(topology: StrategicTopologySnapshot) = topology.traversalEdges.map { LandMarchEdgeMetric(it.id, 20_000_000, 25_000_000) }

    @Test fun `input order does not affect hash and caller mutation cannot alter costs`() {
        val topology = topology()
        val rows = metrics(topology).toMutableList()
        val snapshot = LandMarchMetricSnapshot(topology, hash, rows)
        assertEquals(snapshot.contentHash, LandMarchMetricSnapshot(topology, hash, rows.reversed()).contentHash)
        rows.clear()
        assertEquals(2, snapshot.edgesById.size)
        assertFailsWith<UnsupportedOperationException> { (snapshot.edgesById as MutableMap).clear() }
        assertEquals(30_000_000L, LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
        assertTrue(topology.traversalEdges.all { it.movementCost == 1 })
    }
    @Test fun `missing invented duplicate or mismatched costs fail closed`() {
        val topology = topology(); val rows = metrics(topology)
        assertFailsWith<IllegalArgumentException> { LandMarchMetricSnapshot(topology, hash, rows.drop(1)) }
        assertFailsWith<IllegalArgumentException> { LandMarchMetricSnapshot(topology, hash, rows + LandMarchEdgeMetric("invented", 1, 1)) }
        assertFailsWith<IllegalArgumentException> { LandMarchMetricSnapshot(topology, hash, rows + rows.first()) }
        assertFailsWith<IllegalArgumentException> { LandMarchMetricSnapshot(topology, "b".repeat(64), rows) }
        assertFailsWith<IllegalArgumentException> { LandMarchEdgeMetric("edge", 0, 0) }
        assertFailsWith<IllegalArgumentException> { LandMarchEdgeMetric("edge", 10, 9) }
    }
    @Test fun `changed terrain cost changes metric identity`() {
        val topology = topology(); val rows = metrics(topology)
        assertNotEquals(LandMarchMetricSnapshot(topology, hash, rows).contentHash,
            LandMarchMetricSnapshot(topology, hash, listOf(rows.first().copy(costMm = 25_000_001)) + rows.drop(1)).contentHash)
    }
    @Test fun `water and ferry legs never acquire a land metric fallback`() {
        val edge = topology().traversalEdges.first()
        assertTrue(LandMarchMetricSnapshot.supports(edge.copy(mode = TraversalMode.FORD)))
        assertTrue(LandMarchMetricSnapshot.supports(edge.copy(mode = TraversalMode.BRIDGE)))
        assertFalse(LandMarchMetricSnapshot.supports(edge.copy(mode = TraversalMode.FERRY)))
        assertFalse(LandMarchMetricSnapshot.supports(edge.copy(mode = TraversalMode.EMBARK,
            to = StrategicNodeRef.WaterZone("water"))))
    }

}
