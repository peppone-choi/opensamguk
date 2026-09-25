package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class HwihaCorpsOrderTest {
    private val topology = StrategicTopologySnapshot("qa",setOf("A"),emptyList(),emptyList(),emptyList(),mapOf("qa" to "a".repeat(64)))
    private val order = HwihaCorpsOrder("order",1,2,StrategicNodeRef.LandProvince("A"),topology.topologyRevision,topology.contentHash)
    private val corps = HwihaDeployedCorps("order",1,2,10,1,listOf(20),HwihaPhase(200,1,1))
    private fun read(raw: Any?) = HwihaCorpsOrder.read(mapOf(HwihaCorpsOrder.META_KEY to raw),topology)

    @Test fun `round trip persists destination before movement and absent stays absent`() {
        assertNull(HwihaCorpsOrder.read(emptyMap(),topology))
        assertEquals(order,read(order.toMetaValue()))
        order.requireBinding(corps,2)
        assertEquals(order.toMetaValue().keys.toList(),read(order.toMetaValue())!!.toMetaValue().keys.toList())
    }
    @Test fun `strict schema identities types and bounds reject corruption`() {
        val raw=order.toMetaValue()
        for(bad in listOf(null,emptyMap<String,Any>(),raw-"destination",raw+("extra" to true),raw+("version" to 2),
            raw+("version" to "1"),raw+("orderId" to " "),raw+("orderId" to "x".repeat(129)),
            raw+("ownerGeneralId" to 0),raw+("commanderGeneralId" to -1),raw+("ownerGeneralId" to "1"),
            raw+("commanderGeneralId" to 2.0),raw+("destination" to null))) {
            assertFailsWith<IllegalArgumentException> { read(bad) }
        }
        assertEquals("x".repeat(128),read(raw+("orderId" to "x".repeat(128)))!!.orderId)
    }
    @Test fun `stale pins and unknown land destination cannot be restored`() {
        for(bad in listOf(order.toMetaValue()+("topologyRevision" to "old"),
            order.toMetaValue()+("topologyHash" to "b".repeat(64)),
            order.toMetaValue()+("topologyHash" to "bad"),
            order.toMetaValue()+("destination" to "unknown"))) {
            assertFailsWith<IllegalArgumentException> { read(bad) }
        }
    }
    @Test fun `order owner commander and storage identity must all match`() {
        for(changed in listOf(corps.copy(orderId="other"),corps.copy(ownerGeneralId=3),corps.copy(commanderGeneralId=3))) {
            assertFailsWith<IllegalArgumentException> { order.requireBinding(changed,2) }
        }
        assertFailsWith<IllegalArgumentException> { order.requireBinding(corps,1) }
    }
}
