package opensamguk.logic.world

import kotlin.test.*

class LandMarchProgressRouteTest {
    private val pin = "a".repeat(64)
    private val a = StrategicNodeRef.LandProvince("A")
    private val b = StrategicNodeRef.LandProvince("B")
    private fun edge(id: String, seasonal: Boolean = false) = TraversalEdge(id, a, b, TraversalMode.FORD,
        false, 1, 10, RiskBand.LOW, if (seasonal) SeasonalAvailability.SEASONAL else SeasonalAvailability.ALWAYS,
        sourceRefs=listOf("qa:$id"), confidence=EvidenceConfidence.REVIEWED)
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(),
        listOf(edge("cross", true), edge("detour").copy(mode=TraversalMode.BRIDGE)), emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics = LandMarchMetricSnapshot(topology, pin,
        listOf(LandMarchEdgeMetric("cross", 40, 40), LandMarchEdgeMetric("detour", 80, 80)))
    private fun state(open: Boolean) = StrategicEdgeStateSnapshot(topology.topologyRevision, topology.contentHash,
        mapOf("cross" to StrategicEdgeState(seasonOpen=open)))
    private val path = assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(topology,
        StrategicPathRequest(a,b,1),state(true),metrics)).path
    private fun run(route: ResolvedLandMarchPath = path, cursor: LandMarchCursor = LandMarchCursor(route.pathHash),
        open: Boolean = true, position: StrategicNodeRef = a) = LandMarchProgress.advance(
        topology,metrics,state(open),route,cursor,position,1,30) { LandMarchEntry.CLEAR }
    private fun altered(nodes: List<String> = path.nodeKeys, edges: List<String> = path.edgeIds,
        modes: List<TraversalMode> = path.modes, total: Long = path.totalCostMm) = ResolvedLandMarchPath(
        nodes,edges,modes,total,path.capacity,path.topologyRevision,path.topologyHash,path.metricHash,path.pathHash)

    @Test fun `seasonal exact edge closure cannot substitute an open parallel detour`() {
        assertEquals(listOf("cross"),path.edgeIds)
        val partial=assertIs<LandMarchAdvance.Advanced>(run())
        assertEquals(30L,partial.cursor.paidMm)
        val closed=assertIs<LandMarchAdvance.Advanced>(run(cursor=partial.cursor,open=false))
        assertEquals(LandMarchStop.EDGE_BLOCKED,closed.stop)
        assertEquals(partial.cursor,closed.cursor);assertEquals(0L,closed.spentMm)
        val reopened=assertIs<LandMarchAdvance.Advanced>(run(cursor=closed.cursor))
        assertEquals(LandMarchStop.ARRIVED,reopened.stop);assertEquals(10L,reopened.spentMm)
    }

    @Test fun `unknown node wrong mode disconnected node and false total are rejected`() {
        val malformed=listOf(altered(nodes=listOf("land:A","land:unknown")),
            altered(modes=listOf(TraversalMode.LAND)),altered(nodes=listOf("land:A","land:A")),
            altered(total=41),altered(edges=listOf("unknown")),altered(nodes=listOf("land:A","water:B")))
        for(route in malformed) assertEquals(LandMarchRejection.INVALID_ROUTE,
            assertIs<LandMarchAdvance.Rejected>(run(route)).reason)
    }

    @Test fun `untrusted route total overflow is rejected instead of wrapping`() {
        val reverse=edge("reverse").copy(from=b,to=a,mode=TraversalMode.BRIDGE)
        val t=StrategicTopologySnapshot("large",setOf("A","B"),emptyList(),listOf(edge("cross"),reverse),emptyList(),
            mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
        val m=LandMarchMetricSnapshot(t,pin,listOf(LandMarchEdgeMetric("cross",1,Long.MAX_VALUE),LandMarchEdgeMetric("reverse",1,1)))
        val route=ResolvedLandMarchPath(listOf("land:A","land:B","land:A"),listOf("cross","reverse"),
            listOf(TraversalMode.FORD,TraversalMode.BRIDGE),0,1,t.topologyRevision,t.contentHash,m.contentHash,"b".repeat(64))
        val result=LandMarchProgress.advance(t,m,StrategicEdgeStateSnapshot(t.topologyRevision,t.contentHash,emptyMap()),
            route,LandMarchCursor(route.pathHash),a,1,30) { error("invalid route must never probe encounters") }
        assertEquals(LandMarchRejection.INVALID_ROUTE,assertIs<LandMarchAdvance.Rejected>(result).reason)
    }
}
