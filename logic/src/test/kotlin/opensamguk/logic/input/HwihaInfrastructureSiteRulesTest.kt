package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.DomesticWork
import opensamguk.logic.domestic.WorkRequest
import opensamguk.logic.world.*

class HwihaInfrastructureSiteRulesTest {
    private val topology = StrategicTopologySnapshot("qa", setOf("seat", "piece", "other"), emptyList(), listOf(
        TraversalEdge("road-piece", StrategicNodeRef.LandProvince("piece"), StrategicNodeRef.LandProvince("other"),
            TraversalMode.LAND, false, 1, 7, RiskBand.LOW, SeasonalAvailability.ALWAYS,
            sourceRefs = listOf("qa"), confidence = EvidenceConfidence.REVIEWED)), emptyList(),
        mapOf("qa" to "a".repeat(64)))
    private val gate = StrategicRoadGate("road-piece", 1, 1, 1, 2, 2, false,
        fortCells = listOf(StrategicFortCell("piece", 1, 1)))
    private val county = DomesticCounty(10, "County", 1, "seat", "郡", emptyMap())
    private val state = DomesticProjection(RuleProfile.HWIHA, HwihaPhase(200, 1, 1),
        emptyList(), emptyList(), listOf(county), emptyList(), topology.landProvinceIds,
        provinceIdsByCounty = mapOf(10 to setOf("seat", "piece")))
    private fun infrastructure(active: Boolean, gates: List<StrategicRoadGate> = listOf(gate),
        forts: List<HwihaRoadFort> = emptyList()) = HwihaInfrastructureSiteState(topology, gates,
        StrategicEdgeStateSnapshot(topology.topologyRevision, topology.contentHash,
            mapOf("road-piece" to StrategicEdgeState(active, false, false, 7))), forts)

    @Test fun `a road on a cityless part of the county is buildable only on a mapped grid`() {
        val request = WorkRequest(1, 10, DomesticWork.ROAD, "road-piece")
        assertNull(HwihaInfrastructureSiteRules.error(request, state, infrastructure(false)))
        assertNotNull(HwihaInfrastructureSiteRules.error(request, state, infrastructure(false, emptyList())))
        assertNotNull(HwihaInfrastructureSiteRules.error(request,
            state.copy(provinceIdsByCounty = emptyMap()), infrastructure(false)))
        assertNotNull(HwihaInfrastructureSiteRules.error(request, state, infrastructure(true)))
    }

    @Test fun `legacy county road needs no strategic gate but targeted roads still do`() {
        val legacy = infrastructure(false, emptyList())
        assertNull(HwihaInfrastructureSiteRules.error(WorkRequest(1, 10, DomesticWork.ROAD), state, legacy))
        assertNotNull(HwihaInfrastructureSiteRules.error(WorkRequest(1, 10, DomesticWork.ROAD, "road-piece"), state, legacy))
        assertNotNull(HwihaInfrastructureSiteRules.error(WorkRequest(1, 10, DomesticWork.ROAD), state, infrastructure(false)))
    }

    @Test fun `fort uses the county piece and rejects occupied cells`() {
        val request = WorkRequest(1, 10, DomesticWork.FORTIFICATION, "road-piece", 1, 1)
        assertNull(HwihaInfrastructureSiteRules.error(request, state, infrastructure(true)))
        val fort = HwihaRoadFort(HwihaRoadFort.siteId("road-piece", 1, 1), "road-piece", "piece", 1, 1,
            1, 100, 0)
        assertNotNull(HwihaInfrastructureSiteRules.error(request, state, infrastructure(true, forts = listOf(fort))))
        assertNotNull(HwihaInfrastructureSiteRules.error(request, state, infrastructure(false)))
    }
}
