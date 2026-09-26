package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class MarchStateTest {
    private val pin="a".repeat(64)
    private val a=StrategicNodeRef.LandProvince("A")
    private val b=StrategicNodeRef.LandProvince("B")
    private val topology=StrategicTopologySnapshot("qa",setOf("A","B"),emptyList(),listOf(
        TraversalEdge("ab",a,b,TraversalMode.LAND,false,1,10,RiskBand.LOW,SeasonalAvailability.ALWAYS,
            sourceRefs=listOf("qa:ab"),confidence=EvidenceConfidence.REVIEWED)),emptyList(),mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics=LandMarchMetricSnapshot(topology,pin,listOf(LandMarchEdgeMetric("ab",40,40)))
    private val path=assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(topology,
        StrategicPathRequest(a,b,1),StrategicEdgeStateSnapshot(topology.topologyRevision,topology.contentHash,emptyMap()),metrics)).path
    private val state=MarchState(CountyAssignment("dispatch",1,1,10),path,LandMarchCursor(path.pathHash,0,30),
        Phase(200,1,1),LandMarchStop.BUDGET_EXHAUSTED)
    private fun read(raw: Any?)=MarchState.read(mapOf(MarchState.META_KEY to raw),topology,metrics)

    @Test fun `assignment wire remains flat version one`() {
        val raw = state.toMetaValue()
        assertEquals(setOf("version", "assignment", "path", "edgeIndex", "paidMm", "lastAdvancedAt", "stop"), raw.keys)
        assertEquals(1, raw["version"])
        assertEquals(state.assignment.toMetaValue(), raw["assignment"])
        assertEquals(LandMarchPathCodec.toMetaValue(path), raw["path"])
        assertEquals(0, raw["edgeIndex"])
        assertEquals(30L, raw["paidMm"])
        assertEquals(state.lastAdvancedAt.toMetaValue(), raw["lastAdvancedAt"])
        assertEquals("BUDGET_EXHAUSTED", raw["stop"])
    }

    @Test fun `stored partial progress accepts JSON integer widths without banking unused movement`() {
        for(paid in listOf<Any>(30,30L)) {
            val restored=read(state.toMetaValue()+("paidMm" to paid))!!
            assertEquals(state.assignment,restored.assignment);assertEquals(state.cursor,restored.cursor)
            assertEquals(state.path.pathHash,restored.path.pathHash)
            assertEquals(state.lastAdvancedAt,restored.lastAdvancedAt);assertEquals(state.stop,restored.stop)
        }
        assertNull(MarchState.read(emptyMap(),topology,metrics))
    }

    @Test fun `corrupt cursor fields types phase and false terminal state are rejected`() {
        val good=state.toMetaValue()
        val bad=listOf(null,good+("unusedMm" to 10),good+("paidMm" to 30.0),good+("paidMm" to "30"),
            good+("paidMm" to -1L),good+("paidMm" to 40L),good+("edgeIndex" to 2),good+("edgeIndex" to 0.0),
            good+("stop" to "ARRIVED"),good+("stop" to "ENCOUNTER"),good+("version" to 2),
            good+("lastAdvancedAt" to mapOf("year" to 200,"month" to 13,"phase" to 1)))
        for(raw in bad) assertFailsWith<IllegalArgumentException> { read(raw) }
    }

    @Test fun `arrival and encountered destination remain distinct across restoration`() {
        for(stop in listOf(LandMarchStop.ARRIVED,LandMarchStop.ENCOUNTER)) {
            val terminal=state.copy(cursor=LandMarchCursor(path.pathHash,1,0),stop=stop)
            assertEquals(stop,read(terminal.toMetaValue())!!.stop)
        }
        assertFailsWith<IllegalArgumentException> { state.copy(cursor=LandMarchCursor(path.pathHash,1,0)) }
    }
}
