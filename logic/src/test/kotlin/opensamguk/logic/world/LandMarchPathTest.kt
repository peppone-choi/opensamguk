package opensamguk.logic.world

import kotlin.test.*

class LandMarchPathTest {
    private val tilesHash = "a".repeat(64)
    private fun land(id: String) = StrategicNodeRef.LandProvince(id)
    private fun edge(id: String, from: String, to: String, mode: TraversalMode = TraversalMode.LAND,
        season: SeasonalAvailability = SeasonalAvailability.ALWAYS, capacity: Int = 10) = TraversalEdge(
        id, land(from), land(to), mode, false, 1, capacity, RiskBand.LOW, season,
        sourceRefs = listOf("review:$id"), confidence = EvidenceConfidence.REVIEWED)
    private fun topology(edges: List<TraversalEdge>, barriers: List<RiverBarrier> = emptyList()) =
        StrategicTopologySnapshot("v1", setOf("A","B","C","D"), emptyList(), edges, barriers,
            mapOf(LandMarchMetricSnapshot.TILES_PATH to tilesHash))
    private fun metrics(topology: StrategicTopologySnapshot, costs: Map<String,Long> = emptyMap()) =
        LandMarchMetricSnapshot(topology, tilesHash, topology.traversalEdges.filter(LandMarchMetricSnapshot::supports)
            .map { LandMarchEdgeMetric(it.id, 1, costs[it.id] ?: 10) })
    private fun state(t: StrategicTopologySnapshot, rows: Map<String,StrategicEdgeState> = emptyMap()) =
        StrategicEdgeStateSnapshot(t.topologyRevision,t.contentHash,rows)
    private fun resolve(t: StrategicTopologySnapshot, costs: Map<String,Long> = emptyMap(),
        live: StrategicEdgeStateSnapshot = state(t), capacity: Int = 1) = StrategicPathResolver.resolveLandMarch(
        t,StrategicPathRequest(land("A"),land("D"),capacity),live,metrics(t,costs))

    @Test fun `long single hop loses to short two hop path without changing legacy path or hash`() {
        val t=topology(listOf(edge("direct","A","D"),edge("ab","A","B"),edge("bd","B","D")))
        val request=StrategicPathRequest(land("A"),land("D"),1)
        val before=assertIs<StrategicPathResult.Resolved>(StrategicPathResolver.resolve(t,request,state(t))).path
        val found=assertIs<LandMarchPathResult.Resolved>(resolve(t,mapOf("direct" to 100L,"ab" to 20L,"bd" to 30L))).path
        assertEquals(listOf("ab","bd"),found.edgeIds)
        assertEquals(50L,found.totalCostMm)
        assertEquals(10,found.capacity)
        val after=assertIs<StrategicPathResult.Resolved>(StrategicPathResolver.resolve(t,request,state(t))).path
        assertEquals(before,after)
        assertEquals(listOf("direct"),after.edgeIds)
        assertEquals(1L,after.totalCost)
        assertFailsWith<UnsupportedOperationException> { (found.edgeIds as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (found.nodeKeys as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (found.modes as MutableList).clear() }
    }
    @Test fun `equal millimetre costs use full edge sequence tie break independent of insertion order`() {
        val edges=listOf(edge("z","A","D"),edge("a","A","B"),edge("b","B","D"))
        val first=assertIs<LandMarchPathResult.Resolved>(resolve(topology(edges),mapOf("z" to 20L))).path
        val reordered=assertIs<LandMarchPathResult.Resolved>(resolve(topology(edges.reversed()),mapOf("z" to 20L))).path
        assertEquals(listOf("a","b"),first.edgeIds)
        assertEquals(first.pathHash,reordered.pathHash)
        assertEquals(first.metricHash,reordered.metricHash)
        val changed=assertIs<LandMarchPathResult.Resolved>(resolve(topology(edges),mapOf("z" to 21L))).path
        assertNotEquals(first.pathHash,changed.pathHash)
    }
    @Test fun `season blockade inactive and live capacity use existing execution gates`() {
        val t=topology(listOf(edge("cross","A","D",TraversalMode.FORD,SeasonalAvailability.SEASONAL)))
        assertIs<LandMarchPathResult.Denied>(resolve(t))
        assertIs<LandMarchPathResult.Resolved>(resolve(t,live=state(t,mapOf("cross" to StrategicEdgeState(seasonOpen=true)))))
        for(live in listOf(StrategicEdgeState(seasonOpen=true,blockaded=true),StrategicEdgeState(active=false,seasonOpen=true)))
            assertIs<LandMarchPathResult.Denied>(resolve(t,live=state(t,mapOf("cross" to live))))
        assertEquals(PathDenialCode.NO_TRANSPORT_CAPACITY,assertIs<LandMarchPathResult.Denied>(resolve(t,
            live=state(t,mapOf("cross" to StrategicEdgeState(seasonOpen=true,availableCapacity=1))),capacity=2)).code)
    }
    @Test fun `stale state metric and unknown state edges are rejected`() {
        val t=topology(listOf(edge("direct","A","D")))
        assertEquals(PathDenialCode.TOPOLOGY_REVISION_STALE,assertIs<LandMarchPathResult.Denied>(resolve(t,
            live=StrategicEdgeStateSnapshot("old",t.contentHash,emptyMap()))).code)
        val other=topology(listOf(edge("other","A","D")))
        assertEquals(PathDenialCode.TOPOLOGY_REVISION_STALE,assertIs<LandMarchPathResult.Denied>(
            StrategicPathResolver.resolveLandMarch(t,StrategicPathRequest(land("A"),land("D"),1),state(t),metrics(other))).code)
        assertEquals(PathDenialCode.TOPOLOGY_STATE_INVALID,assertIs<LandMarchPathResult.Denied>(resolve(t,
            live=state(t,mapOf("invented" to StrategicEdgeState())))).code)
    }
    @Test fun `ferry shortcut never acquires a land metric fallback`() {
        val t=topology(listOf(edge("ferry","A","D",TraversalMode.FERRY),edge("ab","A","B"),edge("bd","B","D")))
        assertEquals(listOf("ab","bd"),assertIs<LandMarchPathResult.Resolved>(resolve(t)).path.edgeIds)
        val ferryOnly=topology(listOf(edge("ferry","A","D",TraversalMode.FERRY)))
        assertIs<LandMarchPathResult.Denied>(resolve(ferryOnly))
    }
    @Test fun `embark water disembark shortcut is excluded and water endpoints are denied`() {
        val water=StrategicNodeRef.WaterZone("river")
        val zone=WaterZoneRecord("river",WaterZoneKind.RIVER_REACH,"geometry:river",
            listOf("review:river"),EvidenceConfidence.REVIEWED,seasonalAvailability=SeasonalAvailability.ALWAYS)
        val embark=edge("embark","A","D").copy(to=water,mode=TraversalMode.EMBARK)
        val disembark=edge("disembark","A","D").copy(from=water,mode=TraversalMode.DISEMBARK)
        val t=StrategicTopologySnapshot("v1",setOf("A","D"),listOf(zone),listOf(embark,disembark),emptyList(),
            mapOf(LandMarchMetricSnapshot.TILES_PATH to tilesHash))
        val request=StrategicPathRequest(land("A"),land("D"),1)
        assertIs<StrategicPathResult.Resolved>(StrategicPathResolver.resolve(t,request,state(t)))
        assertIs<LandMarchPathResult.Denied>(StrategicPathResolver.resolveLandMarch(t,request,state(t),metrics(t)))
        assertIs<LandMarchPathResult.Denied>(StrategicPathResolver.resolveLandMarch(t,
            StrategicPathRequest(water,water,1),state(t),metrics(t)))
    }
    @Test fun `diagnostic barrier can explain denial but never become a resolved metric edge`() {
        val barrier=RiverBarrier("river","A","D",listOf("review:river"),EvidenceConfidence.REVIEWED)
        val blocked=topology(emptyList(),listOf(barrier))
        assertEquals(PathDenialCode.RIVER_CROSSING_REQUIRED,assertIs<LandMarchPathResult.Denied>(resolve(blocked)).code)
        val crossing=topology(listOf(edge("bridge","A","D",TraversalMode.BRIDGE)),listOf(barrier))
        assertEquals(listOf("bridge"),assertIs<LandMarchPathResult.Resolved>(resolve(crossing)).path.edgeIds)
    }
    @Test fun `same land node has zero distance and still carries exact pins`() {
        val t=topology(emptyList());val metric=metrics(t)
        val found=assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(t,
            StrategicPathRequest(land("A"),land("A"),1),state(t),metric)).path
        assertEquals(0L,found.totalCostMm);assertTrue(found.edgeIds.isEmpty())
        assertEquals(metric.contentHash,found.metricHash);assertEquals(t.contentHash,found.topologyHash)
        assertEquals(t.topologyRevision,found.topologyRevision)
    }
}
