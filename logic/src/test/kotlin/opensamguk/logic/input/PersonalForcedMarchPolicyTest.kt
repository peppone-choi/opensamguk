package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class PersonalForcedMarchPolicyTest {
    private val a = StrategicNodeRef.LandProvince("A")
    private val b = StrategicNodeRef.LandProvince("B")
    private val pin = "a".repeat(64)
    private fun edge(id: String, from: String, to: String) = TraversalEdge(id,
        StrategicNodeRef.LandProvince(from), StrategicNodeRef.LandProvince(to), TraversalMode.LAND,
        false, 1, 1, RiskBand.LOW, SeasonalAvailability.ALWAYS,
        sourceRefs = listOf("qa"), confidence = EvidenceConfidence.REVIEWED)
    private fun graph(edges: List<TraversalEdge>) = StrategicTopologySnapshot("forced", edges.flatMap {
        listOf((it.from as StrategicNodeRef.LandProvince).id, (it.to as StrategicNodeRef.LandProvince).id)
    }.toSet(), emptyList(), edges, emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private fun meta(topology: StrategicTopologySnapshot) = mapOf(
        LandPassageState.META_KEY to LandPassageState.initialMetaValue(topology),
        MarchReactions.META_KEY to MarchReactions.Empty.toMetaValue())
    private fun assess(topology: StrategicTopologySnapshot, metrics: LandMarchMetricSnapshot,
        condition: PersonalTravelCondition = PersonalTravelCondition.INITIAL,
        worldMeta: Map<String, Any?> = meta(topology), inputId: String = TravelInput.FORCED_MARCH): TravelAssessment =
        TravelRules.assess(TravelRequest(1, inputId, b), b,
            TravelSnapshot(RuleProfile.HWIHA, true, a, false, false, emptySet(),
                mapOf(PersonalTravelCondition.META_KEY to condition.toMetaValue())), topology, metrics, worldMeta)
    private fun single(distance: Long, cost: Long, condition: PersonalTravelCondition = PersonalTravelCondition.INITIAL): TravelAssessment {
        val topology = graph(listOf(edge("ab", "A", "B")))
        return assess(topology, LandMarchMetricSnapshot(topology, pin, listOf(LandMarchEdgeMetric("ab", distance, cost))), condition)
    }

    @Test fun `personal ledger keeps shared normal budget while bounding forced distance and terrain turns`() {
        assertEquals(30_000_000L, LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
        assertEquals(370_000_000L, ForcedMarchTempo.budgetMm)
        assertEquals(260_000_000L, ForcedMarchTempo.maxRouteDistanceMm)
        assertEquals(2L, ForcedMarchTempo.maxEstimatedTurns)
        assertEquals(1L, assertIs<TravelAssessment.Eligible>(single(260_000_000, 370_000_000)).forcedPreview!!.estimatedTurns)
        val two = assertIs<TravelAssessment.Eligible>(single(260_000_000, 740_000_000)).forcedPreview!!
        assertEquals(2L, two.estimatedTurns)
        assertEquals(86, two.forcedFatigueDelta)
        assertEquals(-43, two.forcedMoraleDelta)
        assertEquals(86, two.afterFatigue)
        assertEquals(57, two.afterMorale)
        assertEquals(TravelFailure.FORCED_ROUTE_TOO_LONG,
            assertIs<TravelAssessment.Rejected>(single(260_000_001, 740_000_000)).reason)
        assertEquals(TravelFailure.FORCED_DURATION_EXCEEDED,
            assertIs<TravelAssessment.Rejected>(single(260_000_000, 740_000_001)).reason)
        assertEquals(24_928_032_533L, PersonalForcedMarchPolicy.estimatedTurns(Long.MAX_VALUE))
    }

    @Test fun `partially closed cheaper detour falls back to a legal direct route without loosening caps`() {
        val topology = graph(listOf(edge("ab", "A", "B"), edge("ac", "A", "C"), edge("cb", "C", "B"),
            edge("ad", "A", "D"), edge("db", "D", "B")))
        val metrics = LandMarchMetricSnapshot(topology, pin, listOf(LandMarchEdgeMetric("ab", 240_000_000, 360_000_000),
            LandMarchEdgeMetric("ac", 140_000_000, 140_000_000), LandMarchEdgeMetric("cb", 130_000_000, 130_000_000),
            LandMarchEdgeMetric("ad", 125_000_000, 125_000_000), LandMarchEdgeMetric("db", 125_000_000, 125_000_000)))
        assertEquals(listOf("ad", "db"), assertIs<TravelAssessment.Eligible>(assess(topology, metrics)).path.edgeIds)
        val passage = LandPassageState.initialMetaValue(topology)
        @Suppress("UNCHECKED_CAST") val rows = passage.getValue("edges") as Map<String, Map<String, Any>>
        fun close(ids: Set<String>): Map<String, Any?> = meta(topology) + (LandPassageState.META_KEY to
            (passage + ("edges" to rows.mapValues { (id, row) -> if (id in ids) row + ("active" to false) else row })))
        val closed = close(setOf("ad"))
        val underlying = assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(topology,
            StrategicPathRequest(a, b, 1), LandPassageState.read(closed, topology)!!, metrics)).path
        assertEquals(listOf("ac", "cb"), underlying.edgeIds)
        val forced = assertIs<TravelAssessment.Eligible>(assess(topology, metrics, worldMeta = closed))
        assertEquals(listOf("ab"), forced.path.edgeIds)
        assertEquals(240_000_000L, forced.forcedPreview!!.distanceMm)
        assertEquals(1L, forced.forcedPreview!!.estimatedTurns)
        assertEquals(listOf("ab"), assertIs<TravelAssessment.Eligible>(
            assess(topology, metrics, worldMeta = closed, inputId = TravelInput.MOVE)).path.edgeIds)
        assertEquals(TravelFailure.FORCED_ROUTE_TOO_LONG, assertIs<TravelAssessment.Rejected>(
            assess(topology, metrics, worldMeta = close(setOf("ad", "ab")))).reason)
    }

    @Test fun `raw existing cost must fit capacity with equality and cumulative short distance retained`() {
        val paid = assertIs<TravelAssessment.Eligible>(single(30_000_000, 30_000_000, PersonalTravelCondition(90, 5)))
        assertEquals(100, paid.forcedPreview!!.afterFatigue)
        assertEquals(0, paid.forcedPreview!!.afterMorale)
        for (condition in listOf(PersonalTravelCondition(95, 100), PersonalTravelCondition(0, 4), PersonalTravelCondition(100, 0)))
            assertEquals(TravelFailure.FORCED_MARCH_EXHAUSTED,
                assertIs<TravelAssessment.Rejected>(single(30_000_000, 30_000_000, condition)).reason)
        assertIs<TravelAssessment.Eligible>(single(1, 1, PersonalTravelCondition(100, 0)))
        assertEquals(TravelFailure.FORCED_MARCH_EXHAUSTED, assertIs<TravelAssessment.Rejected>(
            single(1, 1, PersonalTravelCondition(100, 100, 2_999_999))).reason)
        assertEquals(TravelFailure.FORCED_MARCH_EXHAUSTED, assertIs<TravelAssessment.Rejected>(
            single(1, 1, PersonalTravelCondition(0, 0, 5_999_999))).reason)
    }

    @Test fun `two phase preview equals the existing physical increment carry and bounded calculation`() {
        val topology = graph(listOf(edge("ab", "A", "B")))
        val metrics = LandMarchMetricSnapshot(topology, pin, listOf(LandMarchEdgeMetric("ab", 260_000_000, 740_000_000)))
        val initial = PersonalTravelCondition(10, 80, 10_000_000)
        val eligible = assertIs<TravelAssessment.Eligible>(assess(topology, metrics, initial))
        val path = eligible.path
        val start = LandMarchCursor(path.pathHash)
        val middle = LandMarchCursor(path.pathHash, 0, 370_000_000)
        val end = LandMarchCursor(path.pathHash, 1)
        val first = initial.afterForcedMarch(start, middle, path, metrics)
        val result = first.afterForcedMarch(middle, end, path, metrics)
        assertEquals(initial.afterForcedMarch(start, end, path, metrics), result)
        assertEquals(PersonalTravelCondition(97, 36), result)
        val preview = eligible.forcedPreview!!
        assertEquals(result.fatigue, preview.afterFatigue)
        assertEquals(result.morale, preview.afterMorale)
        assertEquals(result.fatigue - initial.fatigue, preview.forcedFatigueDelta)
        assertEquals(result.morale - initial.morale, preview.forcedMoraleDelta)
        // The existing pure clamp contract remains available; admission/execution guard raw cost separately.
        assertEquals(PersonalTravelCondition(100, 0, 20_000_000), PersonalTravelCondition(99, 1).afterForcedMarch(start, end, path, metrics))
    }
}
