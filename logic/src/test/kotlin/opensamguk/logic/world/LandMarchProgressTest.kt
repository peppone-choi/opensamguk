package opensamguk.logic.world

import kotlin.test.*

class LandMarchProgressTest {
    private val pin = "a".repeat(64)
    private fun node(id: String) = StrategicNodeRef.LandProvince(id)
    private fun edge(id: String, a: String, b: String, directed: Boolean = false) = TraversalEdge(
        id, node(a), node(b), TraversalMode.LAND, directed, 1, 10, RiskBand.LOW,
        SeasonalAvailability.ALWAYS, sourceRefs = listOf("qa:$id"), confidence = EvidenceConfidence.REVIEWED)
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B", "C"), emptyList(),
        listOf(edge("ab", "A", "B"), edge("bc", "B", "C")), emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics = LandMarchMetricSnapshot(topology, pin,
        listOf(LandMarchEdgeMetric("ab", 20_000_000, 20_000_000), LandMarchEdgeMetric("bc", 20_000_000, 20_000_000)))
    private fun state(rows: Map<String, StrategicEdgeState> = emptyMap()) =
        StrategicEdgeStateSnapshot(topology.topologyRevision, topology.contentHash, rows)
    private val path = assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(
        topology, StrategicPathRequest(node("A"), node("C"), 1), state(), metrics)).path
    private fun advance(cursor: LandMarchCursor = LandMarchCursor(path.pathHash), position: String = "A",
        live: StrategicEdgeStateSnapshot = state(), budget: Long = LandMarchMetricSnapshot.NORMAL_BUDGET_MM,
        encounter: (StrategicNodeRef.LandProvince) -> LandMarchEntry = { LandMarchEntry.CLEAR }) =
        LandMarchProgress.advance(topology, metrics, live, path, cursor, node(position), 1, budget, encounter)

    @Test fun `thirty km crosses twenty km edge and preserves ten km on next edge`() {
        val first = assertIs<LandMarchAdvance.Advanced>(advance())
        assertEquals(listOf(node("B")), first.reachedNodes)
        assertEquals(LandMarchCursor(path.pathHash, 1, 10_000_000), first.cursor)
        assertEquals(30_000_000L, first.spentMm); assertEquals(0L, first.unusedMm)
        assertEquals(LandMarchStop.BUDGET_EXHAUSTED, first.stop)
        val second = assertIs<LandMarchAdvance.Advanced>(advance(first.cursor, "B"))
        assertEquals(listOf(node("C")), second.reachedNodes)
        assertEquals(LandMarchStop.ARRIVED, second.stop)
        assertEquals(10_000_000L, second.spentMm); assertEquals(20_000_000L, second.unusedMm)
        assertEquals(0L, second.cursor.paidMm)
        assertFailsWith<UnsupportedOperationException> { (first.reachedNodes as MutableList).clear() }
        val again = assertIs<LandMarchAdvance.Advanced>(advance(second.cursor, "C"))
        assertEquals(0L, again.spentMm); assertTrue(again.reachedNodes.isEmpty())
    }

    @Test fun `mid edge closure preserves paid distance and current node until reopened`() {
        val first = assertIs<LandMarchAdvance.Advanced>(advance())
        for (closed in listOf(StrategicEdgeState(active=false), StrategicEdgeState(blockaded=true), StrategicEdgeState(availableCapacity=0))) {
            val blocked = assertIs<LandMarchAdvance.Advanced>(advance(first.cursor, "B", state(mapOf("bc" to closed))))
            assertEquals(LandMarchStop.EDGE_BLOCKED, blocked.stop)
            assertEquals(first.cursor, blocked.cursor); assertTrue(blocked.reachedNodes.isEmpty())
            assertEquals(0L, blocked.spentMm)
        }
        assertEquals(LandMarchStop.ARRIVED, assertIs<LandMarchAdvance.Advanced>(advance(first.cursor, "B")).stop)
    }

    @Test fun `encounter at intermediate node ends this turn before remaining edge`() {
        val seen = mutableListOf<String>()
        val result = assertIs<LandMarchAdvance.Advanced>(advance(encounter = { seen += it.id; if (it.id == "B") LandMarchEntry.ENCOUNTER else LandMarchEntry.CLEAR }))
        assertEquals(listOf("B"), seen); assertEquals(listOf(node("B")), result.reachedNodes)
        assertEquals(LandMarchStop.ENCOUNTER, result.stop)
        assertEquals(LandMarchCursor(path.pathHash, 1, 0), result.cursor)
        assertEquals(20_000_000L, result.spentMm); assertEquals(10_000_000L, result.unusedMm)
    }

    @Test fun `arrival encounter still stops for combat and zero budget never probes next node`() {
        val atEnd = assertIs<LandMarchAdvance.Advanced>(advance(budget=40_000_000, encounter={ if (it.id == "C") LandMarchEntry.ENCOUNTER else LandMarchEntry.CLEAR }))
        assertEquals(LandMarchStop.ENCOUNTER, atEnd.stop)
        val zero = assertIs<LandMarchAdvance.Advanced>(advance(budget=0, encounter={ error("no movement") }))
        assertEquals(LandMarchStop.BUDGET_EXHAUSTED, zero.stop); assertEquals(0L, zero.spentMm)
    }

    @Test fun `missing encounter authority does not mean clear terrain`() {
        val result = assertIs<LandMarchAdvance.Advanced>(advance(encounter={ LandMarchEntry.UNAVAILABLE }))
        assertEquals(LandMarchStop.ENCOUNTER_UNAVAILABLE, result.stop)
        assertEquals(LandMarchCursor(path.pathHash), result.cursor)
        assertEquals(0L, result.spentMm); assertTrue(result.reachedNodes.isEmpty())
        val partlyPaid = LandMarchCursor(path.pathHash, 1, 10_000_000)
        val stopped = assertIs<LandMarchAdvance.Advanced>(advance(partlyPaid, "B", encounter={ LandMarchEntry.UNAVAILABLE }))
        assertEquals(partlyPaid, stopped.cursor); assertEquals(0L, stopped.spentMm)
    }

    @Test fun `cursor and authoritative position cannot silently diverge`() {
        for (cursor in listOf(LandMarchCursor("b".repeat(64)), LandMarchCursor(path.pathHash, 3),
            LandMarchCursor(path.pathHash, 0, 20_000_000), LandMarchCursor(path.pathHash, 2, 1))) {
            assertEquals(LandMarchRejection.INVALID_CURSOR, assertIs<LandMarchAdvance.Rejected>(advance(cursor)).reason)
        }
        assertEquals(LandMarchRejection.POSITION_MISMATCH, assertIs<LandMarchAdvance.Rejected>(advance(position="B")).reason)
    }

    @Test fun `stale pins and invented edge state reject before spending or probing encounters`() {
        val stale = StrategicEdgeStateSnapshot("old", topology.contentHash, emptyMap())
        assertEquals(LandMarchRejection.STALE_PIN, assertIs<LandMarchAdvance.Rejected>(advance(live=stale)).reason)
        assertEquals(LandMarchRejection.INVALID_ROUTE, assertIs<LandMarchAdvance.Rejected>(advance(
            live=state(mapOf("invented" to StrategicEdgeState())))).reason)
        val changed = LandMarchMetricSnapshot(topology, pin,
            listOf(LandMarchEdgeMetric("ab", 1, 1), LandMarchEdgeMetric("bc", 1, 1)))
        val rejected = LandMarchProgress.advance(topology, changed, state(), path, LandMarchCursor(path.pathHash),
            node("A"), 1, 30_000_000) { error("stale metric") }
        assertEquals(LandMarchRejection.STALE_PIN, assertIs<LandMarchAdvance.Rejected>(rejected).reason)
    }

    @Test fun `huge budget cannot overflow and traversal is deterministic`() {
        val one = assertIs<LandMarchAdvance.Advanced>(advance(budget=Long.MAX_VALUE))
        val two = assertIs<LandMarchAdvance.Advanced>(advance(budget=Long.MAX_VALUE))
        assertEquals(40_000_000L, one.spentMm); assertEquals(Long.MAX_VALUE - 40_000_000, one.unusedMm)
        assertEquals(one.cursor, two.cursor); assertEquals(one.reachedNodes, two.reachedNodes)
        assertEquals(one.stop, two.stop)
    }
}
