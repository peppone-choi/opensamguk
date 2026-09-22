package opensamguk.infra.seed

import opensamguk.logic.world.*
import java.security.MessageDigest
import kotlin.test.*

class HanLandMarchMetricJsonTest {
    private val fixture = """{"_meta":{"cols":2,"rows":2,"terrainLegend":{"0":"SEA","1":"PLAIN","2":"MOUNTAIN"}},"provinceRecords":[{"id":"A"},{"id":"B"}],"terrain":["12","00"],"owner":[[0,1],[1,1],[0,1],[1,1]],"cities":[{"col":0,"row":0,"lon":0,"lat":0},{"col":1,"row":1,"lon":1,"lat":1}],"adjacency":{"county":[[0,99]]}}"""
    private fun topology(bytes: ByteArray, mode: TraversalMode = TraversalMode.LAND): StrategicTopologySnapshot {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val edges = projectHanDryLandEdges(listOf("A", "B"), intArrayOf(0, 1), listOf("11"), setOf('1'), emptyList(), hash)
        return StrategicTopologySnapshot("test", setOf("A", "B"), emptyList(), edges.map { it.copy(mode = mode, confidence = EvidenceConfidence.REVIEWED, sourceRefs = listOf("synthetic metric fixture")) },
            emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to hash))
    }
    private fun load(json: String = fixture, mode: TraversalMode = TraversalMode.LAND): LandMarchMetricSnapshot {
        val bytes = json.toByteArray()
        return HanLandMarchMetricJson.load(topology(bytes, mode), bytes)
    }

    @Test fun `water excluded centroid and rough share reproduce physical distance without adjacency edges`() {
        val metric = load().edgesById.values.single()
        assertEquals(111_320_000L, metric.distanceMm)
        assertEquals(139_150_000L, metric.costMm)
        assertEquals(30_000_000L, LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
        assertEquals(load().contentHash, load().contentHash)
        assertEquals(1, topology(fixture.toByteArray()).traversalEdges.single().movementCost)
    }
    @Test fun `ford and bridge measured but ferry never receives land fallback`() {
        assertEquals(load().edgesById.values.single().distanceMm, load(mode = TraversalMode.FORD).edgesById.values.single().distanceMm)
        assertEquals(1, load(mode = TraversalMode.BRIDGE).edgesById.size)
        assertTrue(load(mode = TraversalMode.FERRY).edgesById.isEmpty())
    }
    @Test fun `selected bytes must match topology pin`() {
        assertFailsWith<IllegalArgumentException> {
            HanLandMarchMetricJson.load(topology(fixture.toByteArray()), (fixture + " ").toByteArray())
        }
    }
    @Test fun `malformed owner geometry identity and calibration fail closed`() {
        val invalid = listOf(
            fixture.replace("[0,1],[1,1],[0,1],[1,1]", "[0,1]"),
            fixture.replace("[0,1],[1,1],[0,1],[1,1]", "[0,5]"),
            fixture.replace("[0,1],[1,1],[0,1],[1,1]", "[2,4]"),
            fixture.replace("[0,1],[1,1],[0,1],[1,1]", "[0,0],[1,4]"),
            fixture.replace("\"id\":\"B\"", "\"id\":\"A\""),
            fixture.replace("\"12\"", "\"19\""),
            fixture.replace("\"12\"", "\"00\""),
            fixture.replace("\"rows\":2", "\"rows\":1"),
            fixture.replace("\"col\":1", "\"col\":0"),
            fixture.replace("\"lat\":1", "\"lat\":0"),
            fixture.replace("\"lon\":1", "\"lon\":null"),
            fixture.replace("\"rows\":2", "\"rows\":2,\"rows\":2"),
            fixture.replace("\"cities\":[", "\"cities\":[42,"),
            fixture + " {}"
        )
        invalid.forEachIndexed { index, json -> assertFailsWith<IllegalArgumentException>("invalid case $index") { load(json) } }
    }
    @Test fun `positive millimetres use half up without coercing zero or overflow`() {
        assertEquals(1L, HanLandMarchMetricJson.millimetres(0.0000005))
        assertEquals(2L, HanLandMarchMetricJson.millimetres(0.0000015))
        listOf(0.0, -1.0, 0.00000049, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE,
            Long.MAX_VALUE.toDouble() / 1_000_000.0).forEach {
            assertFailsWith<IllegalArgumentException> { HanLandMarchMetricJson.millimetres(it) }
        }
    }
}
