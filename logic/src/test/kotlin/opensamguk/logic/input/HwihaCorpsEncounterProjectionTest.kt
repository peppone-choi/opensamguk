package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class HwihaCorpsEncounterProjectionTest {
    private fun node(id:String)=StrategicNodeRef.LandProvince(id)
    private val topology=StrategicTopologySnapshot("qa",setOf("A","B","C"),emptyList(),listOf(
        TraversalEdge("ab",node("A"),node("B"),TraversalMode.LAND,false,1,10,RiskBand.LOW,
            SeasonalAvailability.ALWAYS,sourceRefs=listOf("qa"),confidence=EvidenceConfidence.REVIEWED)),emptyList(),
        mapOf(LandMarchMetricSnapshot.TILES_PATH to "a".repeat(64)))
    private val metrics=LandMarchMetricSnapshot(topology,"a".repeat(64),listOf(LandMarchEdgeMetric("ab",40,40)))
    private val path=assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(topology,
        StrategicPathRequest(node("A"),node("B"),1),StrategicEdgeStateSnapshot(topology.topologyRevision,topology.contentHash,emptyMap()),metrics)).path
    private val phase=HwihaPhase(200,1,2)
    private val attacker=HwihaDeployedCorps("attack",1,1,null,1,listOf(11),HwihaPhase(200,1,1))
    private val defender=HwihaDeployedCorps("defend",2,2,null,2,listOf(22),HwihaPhase(200,1,1))
    private val encounter=HwihaCorpsEncounter(HwihaEncounterParticipant.from(attacker),listOf(HwihaEncounterParticipant.from(defender)),
        node("B"),node("A"),phase,topology.topologyRevision,topology.contentHash)
    private val march=HwihaCorpsMarchState("attack",1,1,HwihaMarchCheckpoint(path,LandMarchCursor(path.pathHash,1,0),phase,LandMarchStop.ENCOUNTER))
    private fun people(a:HwihaCorpsEncounter?=encounter,b:HwihaCorpsEncounter?=encounter,m:HwihaCorpsMarchState=march):List<DeploymentPersonSource> =
        listOf(attacker,defender).map { corps ->
            val event=if(corps.ownerGeneralId==1)a else b
            val meta=mutableMapOf<String,Any?>(HwihaDeploymentState.META_KEY to HwihaDeploymentState(listOf(corps)).toMetaValue())
            event?.let { meta[HwihaCorpsEncounter.META_KEY]=it.toMetaValue() }
            if(corps.ownerGeneralId==1)meta[HwihaCorpsMarchState.META_KEY]=m.toMetaValue()
            DeploymentPersonSource(corps.ownerGeneralId,corps.nationId,false,meta)
        }
    private fun build(people:List<DeploymentPersonSource> = people(), defenderNode:String="B") = HwihaDeploymentProjection.build(
        RuleProfile.HWIHA,people,listOf(DeploymentUnit(11,1,10,null),DeploymentUnit(22,2,10,null)),emptyList(),
        GeneralPositionSnapshot.fromTopology(topology,listOf(1,2).map {
            GeneralPositionState(topology.topologyRevision,topology.contentHash,it,node(if(it==1)"B" else defenderNode),1)
        }),topology,metrics)

    @Test fun `matching event locks both attacker and defender independent of source order`() {
        for(p in listOf(people(),people().reversed())) {
            val result=assertNotNull(build(p))
            assertTrue(result.people.single { it.id==1 }.inBattle)
            assertTrue(result.people.single { it.id==2 }.inBattle)
        }
    }
    @Test fun `either missing participant event invalidates the projection`() {
        assertNull(build(people(a=null)));assertNull(build(people(b=null)))
    }
    @Test fun `different otherwise valid events cannot be joined`() {
        assertNull(build(people(b=encounter.copy(phase=phase.plus(1)))))
    }
    @Test fun `participant deployment binding must match current corps`() {
        val bad=encounter.copy(defenders=listOf(encounter.defenders.single().copy(orderId="other")))
        assertNull(build(people(a=bad,b=bad)))
    }
    @Test fun `defender must physically occupy encounter province`() {
        assertNull(build(defenderNode="A"))
    }
    @Test fun `attacker checkpoint phase and approach must match encounter`() {
        assertNull(build(people(m=march.copy(checkpoint=march.checkpoint.copy(lastAdvancedAt=phase.plus(1))))))
        val bad=encounter.copy(approachFrom=node("C"))
        assertNull(build(people(a=bad,b=bad)))
    }
}
