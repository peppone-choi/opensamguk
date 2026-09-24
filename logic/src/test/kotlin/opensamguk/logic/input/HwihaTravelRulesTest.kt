package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.logic.world.*

class HwihaTravelRulesTest {
    @Test fun `travel ledger failure reasons cover admission and execution exactly`() {
        val expected = HwihaTravelFailure.entries.mapTo(sortedSetOf()) { it.name } +
            setOf("UNKNOWN_INPUT", "UNAUTHORIZED", "FORBIDDEN", "INVALID_TURN_SLOT")
        val catalog = HwihaInputCatalog.load()
        for (id in HwihaTravelInput.INPUT_IDS) {
            assertEquals(expected, catalog[id]!!.failureReasons.toSet(), id)
            assertEquals(InputDeliveryState.HANDLER_READY, catalog[id]!!.deliveryState)
        }
    }

    private val origin = StrategicNodeRef.LandProvince("A")
    private val destination = StrategicNodeRef.LandProvince("B")
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(),
        listOf(TraversalEdge("ab", origin, destination, TraversalMode.LAND, false, 1, 7,
            RiskBand.LOW, SeasonalAvailability.ALWAYS, sourceRefs = listOf("qa"), confidence = EvidenceConfidence.REVIEWED)),
        emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to "a".repeat(64)))
    private val metrics = LandMarchMetricSnapshot(topology, "a".repeat(64),
        listOf(LandMarchEdgeMetric("ab", 40_000_000, 40_000_000)))
    private val meta = mapOf(HwihaLandPassageState.META_KEY to HwihaLandPassageState.initialMetaValue(topology),
        HwihaMarchReactions.META_KEY to HwihaMarchReactions.Empty.toMetaValue())
    private val snapshot = HwihaTravelSnapshot(RuleProfile.HWIHA, true, origin, false, false)
    private val request = HwihaTravelRequest(1, HwihaTravelInput.MOVE, destination)

    private fun assess(request: HwihaTravelRequest = this.request, destination: StrategicNodeRef.LandProvince? = this.destination,
        snapshot: HwihaTravelSnapshot = this.snapshot, meta: Map<String, Any?> = this.meta) =
        HwihaTravelRules.assess(request, destination, snapshot, topology, metrics, meta)

    @Test fun `route assessment uses the current land position and executable passage`() {
        assertEquals(listOf("land:A", "land:B"), assertIs<HwihaTravelAssessment.Eligible>(assess()).path.nodeKeys)
        val closed = HwihaLandPassageState.initialMetaValue(topology) + ("edges" to mapOf("ab" to
            mapOf("active" to true, "seasonOpen" to false, "blockaded" to true, "availableCapacity" to 7)))
        assertEquals(HwihaTravelFailure.NO_ROUTE, assertIs<HwihaTravelAssessment.Rejected>(
            assess(meta = meta + (HwihaLandPassageState.META_KEY to closed))).reason)
        assertEquals(HwihaTravelFailure.POSITION_UNAVAILABLE, assertIs<HwihaTravelAssessment.Rejected>(
            assess(snapshot = snapshot.copy(actorNode = null))).reason)
    }

    @Test fun `shared assessment fails closed on conflict and missing authority`() {
        for ((state, failure) in listOf(
            snapshot.copy(profile = RuleProfile.SAMMO) to HwihaTravelFailure.WRONG_RULE_PROFILE,
            snapshot.copy(actorExists = false) to HwihaTravelFailure.ACTOR_NOT_FOUND,
            snapshot.copy(inBattle = true) to HwihaTravelFailure.BATTLE_PENDING,
            snapshot.copy(commandsCorps = true) to HwihaTravelFailure.CORPS_DEPLOYED,
        )) assertEquals(failure, assertIs<HwihaTravelAssessment.Rejected>(assess(snapshot = state)).reason)
        assertEquals(HwihaTravelFailure.INVALID_DESTINATION, assertIs<HwihaTravelAssessment.Rejected>(
            assess(request = request.copy(destination = StrategicNodeRef.LandProvince("missing")),
                destination = StrategicNodeRef.LandProvince("missing"))).reason)
        assertEquals(HwihaTravelFailure.ALREADY_THERE, assertIs<HwihaTravelAssessment.Rejected>(
            assess(request = request.copy(destination = origin), destination = origin)).reason)
        assertEquals(HwihaTravelFailure.INVALID_INPUT, assertIs<HwihaTravelAssessment.Rejected>(
            assess(destination = origin)).reason)
        assertEquals(HwihaTravelFailure.STATE_UNAVAILABLE, assertIs<HwihaTravelAssessment.Rejected>(
            assess(meta = emptyMap())).reason)
    }
}
