package opensamguk.logic.world

import opensamguk.common.constants.CityConst.RawCity
import kotlin.test.*

class MilitarySupplyReachabilityTest {
    private fun land(id: String) = StrategicNodeRef.LandProvince(id)
    private val cities = listOf(SupplyCity(1,1),SupplyCity(2,1),SupplyCity(3,1))
    private val capitals = listOf(SupplyCapital(1,1))
    private val cityConst = InitCityOverrideVariant("military-supply-fixture", listOf(
        RawCity(1,"A","특",100,1,1,1,1,1,"하북",0,0,listOf("B")),
        RawCity(2,"B","특",100,1,1,1,1,1,"하북",0,0,listOf("A","C")),
        RawCity(3,"C","특",100,1,1,1,1,1,"하북",0,0,listOf("B"))))
    private fun network(blocks: Map<Int, Set<String>> = emptyMap(), disconnected: Boolean = false): SpatialSupplyNetwork {
        fun edge(id: String, a: String, b: String) = TraversalEdge(id,land(a),land(b),TraversalMode.LAND,false,1,8,
            RiskBand.LOW,SeasonalAvailability.ALWAYS,true,listOf("test:military"),EvidenceConfidence.REVIEWED)
        val topology=StrategicTopologySnapshot("test-military",setOf("a","b","c"),emptyList(),
            listOf(edge("ab","a","b")) + if(disconnected) emptyList() else listOf(edge("bc","b","c")),
            emptyList(),mapOf("fixture" to "memory"))
        return SpatialSupplyNetwork(intArrayOf(1,1,1),
            if(disconnected) listOf(intArrayOf(1),intArrayOf(0),intArrayOf()) else listOf(intArrayOf(1),intArrayOf(0,2),intArrayOf(1)),
            mapOf(1 to 0,2 to 1,3 to 2),mapOf(3 to SupplyFallbackPolicy(SupplyDisconnectionDecision.PROTECT_GEOMETRY_DEFECT,"test:review")),
            StrategicSupplyNetwork(topology,listOf("a","b","c"),null,militaryBlocksByNation=blocks))
    }
    private fun evaluate(network: SpatialSupplyNetwork) = evaluateSupplyReachability(cities,capitals,cityConst,network)

    @Test fun `hostile corps cuts transit despite city graph and geometry protection`() {
        assertEquals(setOf(1,2,3),evaluate(network()).suppliedCityIds)
        val result=evaluate(network(mapOf(1 to setOf("b"))))
        assertEquals(setOf(1),result.suppliedCityIds)
        assertEquals(listOf(SupplyReachabilityVerdict.MILITARY_CUT,SupplyReachabilityVerdict.MILITARY_CUT),result.rows.drop(1).map { it.verdict })
        assertTrue(result.rows.last().cityGraphSupplied)
    }
    @Test fun `occupied capital cannot seed supply behind a military blockade`() {
        val result=evaluate(network(mapOf(1 to setOf("a"))))
        assertTrue(result.suppliedCityIds.isEmpty())
        assertTrue(result.rows.all { it.verdict == SupplyReachabilityVerdict.MILITARY_CUT })
    }
    @Test fun `foreign blockade does not alter another nations supply`() {
        assertEquals(setOf(1,2,3),evaluate(network(mapOf(2 to setOf("b")))).suppliedCityIds)
    }
    @Test fun `missing geometry plus remote blockade is unavailable rather than guessed supply or loss`() {
        assertEquals(SupplyReachabilityVerdict.CITY_ONLY_PROTECTED,evaluate(network(disconnected=true)).rows.last().verdict)
        assertFailsWith<MilitarySupplyUnavailableException> { evaluate(network(mapOf(1 to setOf("b")),disconnected=true)) }
    }
    @Test fun `reviewed upheld disconnection remains unsupplied instead of stopping settlement`() {
        for(decision in listOf(SupplyDisconnectionDecision.UPHOLD_WATER_ROUTE_ONLY,SupplyDisconnectionDecision.UPHOLD_HISTORICAL_EXCLAVE)) {
            val base=network(mapOf(1 to setOf("b")),disconnected=true)
            val result=evaluate(base.copy(fallbackPolicies=mapOf(3 to SupplyFallbackPolicy(decision,"test:uphold"))))
            assertEquals(setOf(1),result.suppliedCityIds)
            assertEquals(SupplyReachabilityVerdict.SPATIAL_CUT_UPHELD,result.rows.last().verdict)
        }
    }
    @Test fun `direct occupation is conclusive even when original spatial route is absent`() {
        val result=evaluate(network(mapOf(1 to setOf("c")),disconnected=true))
        assertEquals(setOf(1,2),result.suppliedCityIds)
        assertEquals(SupplyReachabilityVerdict.MILITARY_CUT,result.rows.last().verdict)
    }
    @Test fun `unknown military province fails closed and input collections are copied`() {
        assertFailsWith<IllegalArgumentException> { network(mapOf(1 to setOf("invented"))) }
        val provinces=mutableSetOf("b");val input=mutableMapOf(1 to provinces.toSet())
        val snapshot=network(input)
        input.clear();provinces.clear()
        assertEquals(setOf(1),evaluate(snapshot).suppliedCityIds)
    }
}
