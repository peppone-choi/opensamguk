package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import opensamguk.logic.world.*

class HwihaTravelStateTest {
    private val origin = StrategicNodeRef.LandProvince("A")
    private val destination = StrategicNodeRef.LandProvince("B")
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(),
        listOf(TraversalEdge("ab", origin, destination, TraversalMode.LAND, false, 1, 7,
            RiskBand.LOW, SeasonalAvailability.ALWAYS, sourceRefs = listOf("qa"), confidence = EvidenceConfidence.REVIEWED)),
        emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to "a".repeat(64)))
    private val metrics = LandMarchMetricSnapshot(topology, "a".repeat(64),
        listOf(LandMarchEdgeMetric("ab", 40_000_000, 40_000_000)))

    @Test fun `pinned direct route and progress survive a metadata round trip`() {
        val passage = HwihaLandPassageState.read(mapOf(HwihaLandPassageState.META_KEY to
            HwihaLandPassageState.initialMetaValue(topology)), topology)!!
        val path = (StrategicPathResolver.resolveLandMarch(topology,
            StrategicPathRequest(origin, destination, 1), passage, metrics) as LandMarchPathResult.Resolved).path
        val at = HwihaPhase(190, 1, 1)
        val result = LandMarchProgress.advance(topology, metrics, passage, path, LandMarchCursor(path.pathHash), origin,
            1, LandMarchMetricSnapshot.NORMAL_BUDGET_MM) { LandMarchEntry.CLEAR } as LandMarchAdvance.Advanced
        val state = HwihaTravelState("request-1", HwihaTravelInput.MOVE, destination,
            HwihaMarchCheckpoint(path, result.cursor, at, result.stop))
        assertEquals(0, state.checkpoint.cursor.edgeIndex)
        assertEquals(30_000_000L, state.checkpoint.cursor.paidMm)
        assertEquals(state.toMetaValue(), HwihaTravelState.read(
            mapOf(HwihaTravelState.META_KEY to state.toMetaValue()), topology, metrics)!!.toMetaValue())
        val corrupted = state.toMetaValue() + ("destinationProvinceId" to "A")
        assertFailsWith<IllegalArgumentException> {
            HwihaTravelState.read(mapOf(HwihaTravelState.META_KEY to corrupted), topology, metrics)
        }
    }
}
