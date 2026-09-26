package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class LandPassageStateTest {
    private fun n(id: String) = StrategicNodeRef.LandProvince(id)
    private fun edge(id: String, mode: TraversalMode, season: SeasonalAvailability) = TraversalEdge(
        id,n("A"),n("B"),mode,false,1,7,RiskBand.LOW,season,sourceRefs=listOf("qa"),confidence=EvidenceConfidence.REVIEWED)
    private val topology = StrategicTopologySnapshot("qa",setOf("A","B"),emptyList(),listOf(
        edge("land",TraversalMode.LAND,SeasonalAvailability.ALWAYS),
        edge("ford",TraversalMode.FORD,SeasonalAvailability.SEASONAL),
        edge("bridge",TraversalMode.BRIDGE,SeasonalAvailability.CLOSED)),emptyList(),mapOf("qa" to "a".repeat(64)))
    private fun initial() = LandPassageState.initialMetaValue(topology)
    private fun read(raw: Any?) = LandPassageState.read(mapOf(LandPassageState.META_KEY to raw),topology)
    private fun changed(id: String, key: String, value: Any?): Map<String,Any?> {
        val raw=initial();val rows=(raw.getValue("edges") as Map<*,*>).toMutableMap()
        rows[id]=(rows.getValue(id) as Map<*,*>)+ (key to value)
        return raw+ ("edges" to rows)
    }
    @Test fun `initial authority explicitly covers all supported edges in sorted order`() {
        val state=read(initial())!!
        assertEquals(listOf("bridge","ford","land"),state.edgeStates.keys.toList())
        assertEquals(StrategicEdgeState(true,false,false,7),state.edgeStates.getValue("land"))
        for(id in listOf("bridge","ford")) assertFalse(state.edgeStates.getValue(id).active)
        assertEquals(topology.contentHash,state.topologyHash)
    }
    @Test fun `missing schema pin partial extra and malformed authority fail closed`() {
        assertNull(LandPassageState.read(emptyMap(),topology))
        val raw=initial();val rows=raw.getValue("edges") as Map<*,*>
        for(bad in listOf(null,raw-"edges",raw+("version" to 2),raw+("version" to "1"),
            raw+("topologyHash" to "f".repeat(64)),raw+("topologyRevision" to "old"),
            raw+("edges" to (rows-"land")),raw+("edges" to (rows+("unknown" to emptyMap<String,Any>()))),
            raw+("extra" to true),changed("land","active",1),changed("land","seasonOpen","false"),
            changed("land","blockaded",null),changed("land","availableCapacity",1.0),
            changed("land","availableCapacity",-1),changed("land","availableCapacity",8))) {
            assertFailsWith<IllegalArgumentException> { read(bad) }
        }
    }
    @Test fun `live closure and reduced capacity restore without initial defaults`() {
        assertTrue(read(changed("land","blockaded",true))!!.edgeStates.getValue("land").blockaded)
        assertEquals(0,read(changed("land","availableCapacity",0))!!.edgeStates.getValue("land").availableCapacity)
        assertFalse(read(changed("land","active",false))!!.edgeStates.getValue("land").active)
        val season=changed("ford","active",true)
        val rows=season["edges"] as Map<*,*>
        val opened=season+("edges" to (rows+("ford" to ((rows["ford"] as Map<*,*>)+("seasonOpen" to true)))))
        assertEquals(StrategicEdgeState(true,true,false,7),read(opened)!!.edgeStates.getValue("ford"))
    }
    @Test fun `road completion activates only the selected crossing and survives round trip`() {
        val closed = changed("land", "active", false)
        val value = LandPassageState.activate(mapOf(LandPassageState.META_KEY to closed), topology, "land")
        val restored = read(value)!!
        assertTrue(restored.edgeStates.getValue("land").active)
        assertFalse(restored.edgeStates.getValue("ford").active)
        assertFailsWith<IllegalArgumentException> {
            LandPassageState.activate(mapOf(LandPassageState.META_KEY to closed), topology, "unknown")
        }
    }
}
