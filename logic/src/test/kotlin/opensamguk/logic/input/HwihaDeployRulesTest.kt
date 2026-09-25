package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class HwihaDeployRulesTest {
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
        HwihaMarchReactions.META_KEY to HwihaMarchReactions.Empty.toMetaValue())
    @Test fun `new order requires a known destination and currently passable route`() {
        assertIs<DeploymentAssessment.Eligible>(HwihaDeployRules.assess(request,state,topology,meta,metrics))
        val closed = LandPassageState.initialMetaValue(topology) + ("edges" to mapOf("ab" to
            mapOf("active" to true,"seasonOpen" to false,"blockaded" to true,"availableCapacity" to 7)))
        assertEquals(DeploymentFailure.NO_ROUTE,assertIs<DeploymentAssessment.Rejected>(HwihaDeployRules.assess(
            request,state,topology,meta+(LandPassageState.META_KEY to closed),metrics)).reason)
        assertEquals(DeploymentFailure.INVALID_DESTINATION,assertIs<DeploymentAssessment.Rejected>(
            HwihaDeployRules.assess(request.copy(destination=StrategicNodeRef.LandProvince("unknown")),state,topology,meta,metrics)).reason)
    }
    @Test fun `authority and deployment relationship are rechecked by the shared contract`() {
        for (bad in listOf(emptyMap(),meta-LandPassageState.META_KEY,meta-HwihaMarchReactions.META_KEY,
            meta+(LandPassageState.META_KEY to mapOf("version" to 9)),
            meta+(HwihaMarchReactions.META_KEY to mapOf("version" to 9)))) {
            assertEquals(DeploymentFailure.STATE_UNAVAILABLE,assertIs<DeploymentAssessment.Rejected>(
                HwihaDeployRules.assess(request,state,topology,bad,metrics)).reason)
        }
        assertEquals(DeploymentFailure.COMMANDER_CHANGED,assertIs<DeploymentAssessment.Rejected>(
            HwihaDeployRules.assess(request,state.copy(units=listOf(DeploymentUnit(7,1,100,4))),topology,meta,metrics)).reason)
        assertEquals(DeploymentFailure.WRONG_RULE_PROFILE,assertIs<DeploymentAssessment.Rejected>(
            HwihaDeployRules.assess(request,state.copy(profile=RuleProfile.SAMMO),topology,meta,metrics)).reason)
    }
}
