package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class DeployRulesTest {
    private val node = StrategicNodeRef.LandProvince("A")
    private val topology = StrategicTopologySnapshot("qa", setOf("A","B"), emptyList(),
        listOf(TraversalEdge("ab",node,StrategicNodeRef.LandProvince("B"),TraversalMode.LAND,false,1,7,
            RiskBand.LOW,SeasonalAvailability.ALWAYS,sourceRefs=listOf("qa"),confidence=EvidenceConfidence.REVIEWED)),
        emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to "a".repeat(64)))
    private val metrics = LandMarchMetricSnapshot(topology,"a".repeat(64),listOf(LandMarchEdgeMetric("ab",40_000_000,40_000_000)))
    private val state = DeploymentProjection(RuleProfile.HWIHA, listOf(DeploymentPerson(1,1,false,node,false)),
        listOf(DeploymentUnit(7,1,100,null)),emptyList(),emptyList())
    private val request = DeployInput(1,listOf(7),StrategicNodeRef.LandProvince("B"))
    private val meta = mapOf(LandPassageState.META_KEY to LandPassageState.initialMetaValue(topology),
        MarchReactions.META_KEY to MarchReactions.Empty.toMetaValue())
    @Test fun `new order requires a known destination and currently passable route`() {
        assertIs<DeploymentAssessment.Eligible>(DeployRules.assess(request,state,topology,meta,metrics))
        val closed = LandPassageState.initialMetaValue(topology) + ("edges" to mapOf("ab" to
            mapOf("active" to true,"seasonOpen" to false,"blockaded" to true,"availableCapacity" to 7)))
        assertEquals(DeploymentFailure.NO_ROUTE,assertIs<DeploymentAssessment.Rejected>(DeployRules.assess(
            request,state,topology,meta+(LandPassageState.META_KEY to closed),metrics)).reason)
        assertEquals(DeploymentFailure.INVALID_DESTINATION,assertIs<DeploymentAssessment.Rejected>(
            DeployRules.assess(request.copy(destination=StrategicNodeRef.LandProvince("unknown")),state,topology,meta,metrics)).reason)
    }
    @Test fun `authority and deployment relationship are rechecked by the shared contract`() {
        for (bad in listOf(emptyMap(),meta-LandPassageState.META_KEY,meta-MarchReactions.META_KEY,
            meta+(LandPassageState.META_KEY to mapOf("version" to 9)),
            meta+(MarchReactions.META_KEY to mapOf("version" to 9)))) {
            assertEquals(DeploymentFailure.STATE_UNAVAILABLE,assertIs<DeploymentAssessment.Rejected>(
                DeployRules.assess(request,state,topology,bad,metrics)).reason)
        }
        assertEquals(DeploymentFailure.COMMANDER_CHANGED,assertIs<DeploymentAssessment.Rejected>(
            DeployRules.assess(request,state.copy(units=listOf(DeploymentUnit(7,1,100,4))),topology,meta,metrics)).reason)
        assertEquals(DeploymentFailure.WRONG_RULE_PROFILE,assertIs<DeploymentAssessment.Rejected>(
            DeployRules.assess(request,state.copy(profile=RuleProfile.SAMMO),topology,meta,metrics)).reason)
    }
    @Test fun `batched options preserve admission failure precedence and physical paths`() {
        val requests = listOf(request, request.copy(destination = node),
            request.copy(destination = StrategicNodeRef.LandProvince("unknown")),
            request.copy(bugokIds = listOf(999)), request)
        for (worldMeta in listOf(meta, emptyMap(), meta - MarchReactions.META_KEY)) {
            val single = requests.map { DeployRules.assessRoute(it, state, topology, worldMeta, metrics) }
            val batch = DeployRules.assessRoutes(requests, state, topology, worldMeta, metrics)
            assertEquals(single.map { it.assessment }, batch.map { it.assessment })
            assertEquals(single.map { it.path?.pathHash }, batch.map { it.path?.pathHash })
            assertEquals(DeploymentFailure.INVALID_DESTINATION,
                assertIs<DeploymentAssessment.Rejected>(batch[2].assessment).reason)
            assertEquals(DeploymentFailure.UNIT_UNAVAILABLE,
                assertIs<DeploymentAssessment.Rejected>(batch[3].assessment).reason)
        }
        val result = DeployRules.assessRoutes(requests, state, topology, meta, metrics)
        assertEquals(40_000_000L, result[0].path!!.totalCostMm)
        assertEquals(0L, result[1].path!!.totalCostMm)
        val closed = StrategicEdgeStateSnapshot(topology.topologyRevision, topology.contentHash,
            mapOf("ab" to StrategicEdgeState(blockaded = true)))
        val blocked = DeployRules.assessRoutes(requests, state, topology, meta, metrics, closed)
        assertEquals(DeploymentFailure.NO_ROUTE,
            assertIs<DeploymentAssessment.Rejected>(blocked[0].assessment).reason)
        assertIs<DeploymentAssessment.Eligible>(blocked[1].assessment)
    }

}
