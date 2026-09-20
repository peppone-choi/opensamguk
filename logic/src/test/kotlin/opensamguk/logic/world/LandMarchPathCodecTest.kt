package opensamguk.logic.world

import kotlin.test.*
import kotlinx.serialization.json.*

class LandMarchPathCodecTest {
    private val pin = "a".repeat(64)
    private fun node(id: String) = StrategicNodeRef.LandProvince(id)
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B", "C"), emptyList(),
        listOf("ab" to ("A" to "B"), "bc" to ("B" to "C")).map { (id, pair) ->
            TraversalEdge(id, node(pair.first), node(pair.second), TraversalMode.LAND, false, 1, 10,
                RiskBand.LOW, SeasonalAvailability.ALWAYS, sourceRefs = listOf("qa:$id"), confidence = EvidenceConfidence.REVIEWED)
        }, emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics = LandMarchMetricSnapshot(topology, pin,
        listOf(LandMarchEdgeMetric("ab", 20, 20), LandMarchEdgeMetric("bc", 20, 20)))
    private fun state(rows: Map<String, StrategicEdgeState> = emptyMap()) = StrategicEdgeStateSnapshot(topology.topologyRevision, topology.contentHash, rows)
    private val path = assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(topology,
        StrategicPathRequest(node("A"), node("C"), 1), state(mapOf("ab" to StrategicEdgeState(availableCapacity = 3))), metrics)).path
    private fun restore(raw: Any?) = LandMarchPathCodec.restore(raw, topology, metrics)

    @Test fun `JSON integer widths round trip the selected path and preserve live capacity`() {
        val saved = LandMarchPathCodec.toMetaValue(path)
        val json = buildJsonObject {
            saved.forEach { (key, value) -> put(key, when(value) {
                is List<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value as String)
            }) }
        }.toString()
        val decoded = Json.parseToJsonElement(json).jsonObject.mapValues { (_, value) -> when(value) {
            is JsonArray -> value.map { it.jsonPrimitive.content }
            else -> value.jsonPrimitive.let { if(it.isString) it.content else it.intOrNull ?: it.long }
        } }
        assertEquals(saved, LandMarchPathCodec.toMetaValue(restore(decoded)))
        assertEquals(path.pathHash, restore(saved + mapOf("version" to 1L, "capacity" to 3L, "totalCostMm" to 40)).pathHash)
        assertEquals(3, restore(decoded).capacity)
    }
    @Test fun `malformed metadata costs modes identities and pins are rejected`() {
        val raw = LandMarchPathCodec.toMetaValue(path)
        listOf(null, emptyMap<String, Any>(), raw + ("extra" to 1), raw - "capacity",
            raw + ("version" to 2), raw + ("capacity" to 3.0), raw + ("totalCostMm" to "40"),
            raw + ("totalCostMm" to 41), raw + ("pathHash" to "b".repeat(64)),
            raw + ("metricHash" to "b".repeat(64)), raw + ("topologyHash" to "b".repeat(64)),
            raw + ("nodeKeys" to listOf("land:C", "land:B", "land:A")),
            raw + ("nodeKeys" to listOf("land:A", "land:B", "land:A")),
            raw + ("nodeKeys" to listOf("land:A", "land:B", "land:missing")),
            raw + ("edgeIds" to listOf("ab", "missing")), raw + ("modes" to listOf("LAND", "FERRY")),
            raw + ("capacity" to 11), raw + ("version" to true)).forEach {
            assertFailsWith<IllegalArgumentException> { restore(it) }
        }
        val changed = LandMarchMetricSnapshot(topology, pin,
            listOf(LandMarchEdgeMetric("ab", 20, 21), LandMarchEdgeMetric("bc", 20, 20)))
        assertFailsWith<IllegalArgumentException> { LandMarchPathCodec.restore(raw, topology, changed) }
    }
    @Test fun `closed route restores unchanged and advance keeps partial progress blocked`() {
        val restored = restore(LandMarchPathCodec.toMetaValue(path))
        val cursor = LandMarchCursor(restored.pathHash, 0, 10)
        val blocked = assertIs<LandMarchAdvance.Advanced>(LandMarchProgress.advance(topology, metrics,
            state(mapOf("ab" to StrategicEdgeState(active = false))), restored, cursor, node("A"), 1, 30) { error("closed edge cannot enter") })
        assertEquals(LandMarchStop.EDGE_BLOCKED, blocked.stop)
        assertEquals(cursor, blocked.cursor); assertEquals(0L, blocked.spentMm)
    }
    @Test fun `overflowing edge total cannot wrap into a stored cost`() {
        val huge = LandMarchMetricSnapshot(topology, pin,
            listOf(LandMarchEdgeMetric("ab", Long.MAX_VALUE, Long.MAX_VALUE), LandMarchEdgeMetric("bc", 20, 20)))
        val raw = LandMarchPathCodec.toMetaValue(path) + ("metricHash" to huge.contentHash)
        val failure = assertFailsWith<IllegalArgumentException> { LandMarchPathCodec.restore(raw, topology, huge) }
        assertEquals("March total overflow", failure.message)
    }
    @Test fun `zero leg path round trips without invented edges`() {
        val zero = assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(topology,
            StrategicPathRequest(node("A"), node("A"), 1), state(), metrics)).path
        assertEquals(0L, restore(LandMarchPathCodec.toMetaValue(zero)).totalCostMm)
    }
}
