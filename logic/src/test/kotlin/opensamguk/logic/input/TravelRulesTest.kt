package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.logic.world.*

class TravelRulesTest {
    @Test fun `travel ledger failure reasons cover admission and execution exactly`() {
        val expected = TravelFailure.entries.mapTo(sortedSetOf()) { it.name } +
            setOf("UNKNOWN_INPUT", "UNAUTHORIZED", "FORBIDDEN", "INVALID_TURN_SLOT")
        val catalog = InputCatalog.load()
        for (id in TravelInput.INPUT_IDS) {
            assertEquals(expected, catalog[id]!!.failureReasons.toSet(), id)
            assertEquals(InputDeliveryState.UI_READY, catalog[id]!!.deliveryState)
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
    private val meta = mapOf(LandPassageState.META_KEY to LandPassageState.initialMetaValue(topology),
        MarchReactions.META_KEY to MarchReactions.Empty.toMetaValue())
    private val snapshot = TravelSnapshot(RuleProfile.HWIHA, true, origin, false, false)
    private val request = TravelRequest(1, TravelInput.MOVE, destination)

    private fun assess(request: TravelRequest = this.request, destination: StrategicNodeRef.LandProvince? = this.destination,
        snapshot: TravelSnapshot = this.snapshot, meta: Map<String, Any?> = this.meta) =
        TravelRules.assess(request, destination, snapshot, topology, metrics, meta)

    @Test fun `route assessment uses the current land position and executable passage`() {
        assertEquals(listOf("land:A", "land:B"), assertIs<TravelAssessment.Eligible>(assess()).path.nodeKeys)
        val closed = LandPassageState.initialMetaValue(topology) + ("edges" to mapOf("ab" to
            mapOf("active" to true, "seasonOpen" to false, "blockaded" to true, "availableCapacity" to 7)))
        assertEquals(TravelFailure.NO_ROUTE, assertIs<TravelAssessment.Rejected>(
            assess(meta = meta + (LandPassageState.META_KEY to closed))).reason)
        assertEquals(TravelFailure.POSITION_UNAVAILABLE, assertIs<TravelAssessment.Rejected>(
            assess(snapshot = snapshot.copy(actorNode = null))).reason)
    }

    @Test fun `shared assessment fails closed on conflict and missing authority`() {
        for ((state, failure) in listOf(
            snapshot.copy(profile = RuleProfile.SAMMO) to TravelFailure.WRONG_RULE_PROFILE,
            snapshot.copy(actorExists = false) to TravelFailure.ACTOR_NOT_FOUND,
            snapshot.copy(inBattle = true) to TravelFailure.BATTLE_PENDING,
            snapshot.copy(commandsCorps = true) to TravelFailure.CORPS_DEPLOYED,
        )) assertEquals(failure, assertIs<TravelAssessment.Rejected>(assess(snapshot = state)).reason)
        assertEquals(TravelFailure.INVALID_DESTINATION, assertIs<TravelAssessment.Rejected>(
            assess(request = request.copy(destination = StrategicNodeRef.LandProvince("missing")),
                destination = StrategicNodeRef.LandProvince("missing"))).reason)
        assertEquals(TravelFailure.ALREADY_THERE, assertIs<TravelAssessment.Rejected>(
            assess(request = request.copy(destination = origin), destination = origin)).reason)
        assertEquals(TravelFailure.INVALID_INPUT, assertIs<TravelAssessment.Rejected>(
            assess(destination = origin)).reason)
        assertEquals(TravelFailure.STATE_UNAVAILABLE, assertIs<TravelAssessment.Rejected>(
            assess(meta = emptyMap())).reason)
    }
}
