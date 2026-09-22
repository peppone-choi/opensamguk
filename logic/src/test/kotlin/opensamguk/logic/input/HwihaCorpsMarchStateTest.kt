package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class HwihaCorpsMarchStateTest {
    private val pin = "a".repeat(64)
    private val a = StrategicNodeRef.LandProvince("A")
    private val b = StrategicNodeRef.LandProvince("B")
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(), listOf(
        TraversalEdge("ab", a, b, TraversalMode.LAND, false, 1, 10, RiskBand.LOW, SeasonalAvailability.ALWAYS,
            sourceRefs = listOf("qa:ab"), confidence = EvidenceConfidence.REVIEWED)), emptyList(),
        mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics = LandMarchMetricSnapshot(topology, pin, listOf(LandMarchEdgeMetric("ab", 40, 40)))
    private val path = assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(topology,
        StrategicPathRequest(a, b, 1), StrategicEdgeStateSnapshot(topology.topologyRevision, topology.contentHash, emptyMap()), metrics)).path
    private val phase = HwihaPhase(200, 1, 2)
    private val corps = HwihaDeployedCorps("order", 1, 2, 3, 1, listOf(4, 5), HwihaPhase(200, 1, 1))
    private val checkpoint = HwihaMarchCheckpoint(path, LandMarchCursor(path.pathHash, 0, 30), phase, LandMarchStop.BUDGET_EXHAUSTED)
    private val state = HwihaCorpsMarchState("order", 1, 2, checkpoint)
    private fun read(raw: Any?) = HwihaCorpsMarchState.read(mapOf(HwihaCorpsMarchState.META_KEY to raw), topology, metrics)

    @Test fun `partial checkpoint restores against the exact deployment without county assignment`() {
        assertNull(HwihaCorpsMarchState.read(emptyMap(), topology, metrics))
        for (paid in listOf<Any>(30, 30L)) {
            val restored = read(state.toMetaValue() + ("checkpoint" to (checkpoint.toMetaValue() + ("paidMm" to paid))))!!
            restored.requireBinding(corps, 2)
            assertEquals(state.deploymentOrderId, restored.deploymentOrderId)
            assertEquals(state.ownerGeneralId, restored.ownerGeneralId)
            assertEquals(state.commanderGeneralId, restored.commanderGeneralId)
            assertEquals(checkpoint.cursor, restored.checkpoint.cursor)
            assertEquals(path.pathHash, restored.checkpoint.path.pathHash)
            assertEquals(phase, restored.checkpoint.lastAdvancedAt)
            assertEquals(checkpoint.stop, restored.checkpoint.stop)
        }
    }

    @Test fun `valid checkpoint cannot be reused by another order owner commander or earlier phase`() {
        assertFailsWith<IllegalArgumentException> { state.requireBinding(corps, 1) }
        assertFailsWith<IllegalArgumentException> { state.requireBinding(corps.copy(orderId = "next"), 2) }
        assertFailsWith<IllegalArgumentException> { state.requireBinding(corps.copy(ownerGeneralId = 9), 2) }
        assertFailsWith<IllegalArgumentException> { state.requireBinding(corps.copy(commanderGeneralId = 9), 2) }
        assertFailsWith<IllegalArgumentException> { state.requireBinding(corps.copy(startedAt = HwihaPhase(200, 1, 3)), 2) }
    }

    @Test fun `strict schema rejects malformed identity checkpoint overpayment and unknown fields`() {
        val raw = state.toMetaValue()
        val bad = listOf(null, raw - "commanderGeneralId", raw + ("version" to 2), raw + ("ownerGeneralId" to 1L),
            raw + ("commanderGeneralId" to 0), raw + ("deploymentOrderId" to " "), raw + ("assignment" to emptyMap<String, Any>()),
            raw + ("checkpoint" to null)) + listOf(
            checkpoint.toMetaValue() + ("paidMm" to 40L), checkpoint.toMetaValue() + ("paidMm" to 30.0),
            checkpoint.toMetaValue() + ("stop" to "ARRIVED"), checkpoint.toMetaValue() + ("stop" to "ENCOUNTER"),
            checkpoint.toMetaValue() + ("unusedMm" to 10L), checkpoint.toMetaValue() + ("edgeIndex" to 2),
        ).map { raw + ("checkpoint" to it) }
        for (value in bad) assertFailsWith<IllegalArgumentException> { read(value) }
    }

    @Test fun `encounter at final destination stays pending instead of becoming arrival`() {
        for (stop in listOf(LandMarchStop.ENCOUNTER, LandMarchStop.ARRIVED)) {
            val terminal = state.copy(checkpoint = checkpoint.copy(cursor = LandMarchCursor(path.pathHash, 1, 0), stop = stop))
            val restored = read(terminal.toMetaValue())!!
            restored.requireBinding(corps, 2)
            assertEquals(stop, restored.checkpoint.stop)
        }
    }
}
