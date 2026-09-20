package opensamguk.infra.seed

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*
import opensamguk.logic.world.*

class HanProvinceCellJsonTest {
    private val fixture = """{"_meta":{"cols":3,"rows":2,"terrainLegend":{"0":"SEA","1":"PLAIN","3":"RIVER"}},"provinceRecords":[{"id":"B"},{"id":"A"}],"terrain":["130","011"],"owner":[[0,1],[-1,1],[1,1],[0,1],[-1,1],[0,1]]}"""
    private fun topology(bytes: ByteArray): StrategicTopologySnapshot = StrategicTopologySnapshot("qa",setOf("A","B"),
        emptyList(),emptyList(),emptyList(),mapOf(LandMarchMetricSnapshot.TILES_PATH to hash(bytes)))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun load(raw: String = fixture): HanProvinceCellIndex {
        val bytes = raw.toByteArray(); return HanProvinceCellJson.load(topology(bytes),bytes)
    }

    @Test fun `stable province identities preserve disconnected and owned water cells in row major order`() {
        val index = load()
        assertEquals(listOf(HanProvinceCell(0,0,'1'),HanProvinceCell(0,1,'0'),HanProvinceCell(2,1,'1')),index.cellsOf("B"))
        assertEquals(listOf(HanProvinceCell(2,0,'0')),index.cellsOf("A"))
        assertEquals(mapOf('0' to "SEA",'1' to "PLAIN",'3' to "RIVER"),index.terrainLegend)
        assertEquals(hash(fixture.toByteArray()),index.tilesContentHash)
        assertEquals(topology(fixture.toByteArray()).contentHash,index.topologyHash)
        assertFailsWith<IllegalArgumentException> { index.cellsOf("missing") }
    }

    @Test fun `selected tile bytes must match exact topology pin`() {
        assertFailsWith<IllegalArgumentException> {
            HanProvinceCellJson.load(topology(fixture.toByteArray()),(fixture+" ").toByteArray())
        }
    }

    @Test fun `invalid dimensions rle terrain and province identity reject without coercion`() {
        val invalid = listOf(
            fixture.replace("\"cols\":3","\"cols\":0"), fixture.replace("\"rows\":2","\"rows\":-1"),
            fixture.replace("\"cols\":3","\"cols\":2147483647"),fixture.replace("\"rows\":2","\"rows\":2.0"),
            fixture.replace("\"rows\":2","\"rows\":\"2\""),fixture.replace("\"rows\":2","\"rows\":2,\"rows\":2"),
            fixture.replace("[0,1],[-1,1],[1,1],[0,1],[-1,1],[0,1]","[0,5]"),
            fixture.replace("[0,1],[-1,1],[1,1],[0,1],[-1,1],[0,1]","[0,7]"),
            fixture.replace("[0,1]","[0,0]"),fixture.replace("[0,1]","[-2,1]"),
            fixture.replace("[0,1]","[2,1]"),fixture.replace("[0,1]","[0,1.0]"),
            fixture.replace("[0,1]","[0,1,0]"),fixture.replace("\"id\":\"A\"","\"id\":\"B\""),
            fixture.replace("\"id\":\"A\"","\"id\":\"unknown\""),fixture.replace("\"id\":\"A\"","\"id\":1"),
            fixture.replace("\"130\"","\"190\""),fixture.replace("\"130\"","\"13\""),
            fixture.replace("\"PLAIN\"","\"ELEVATION\""),fixture.replace("\"3\":\"RIVER\"","\"33\":\"RIVER\""),
            fixture+" {}")
        invalid.forEachIndexed { i, raw -> assertFailsWith<IllegalArgumentException>("case $i") { load(raw) } }
    }

    @Test fun `model copies collections and rejects duplicate cells ordering and bounds`() {
        val cells = mutableListOf(HanProvinceCell(0,0,'1'))
        val legend = mutableMapOf('1' to "PLAIN")
        val provinces = mutableMapOf<String,List<HanProvinceCell>>("A" to cells)
        val index = HanProvinceCellIndex("qa","a".repeat(64),"b".repeat(64),2,2,legend,provinces)
        cells.clear(); legend.clear(); provinces.clear()
        assertEquals(listOf(HanProvinceCell(0,0,'1')),index.cellsOf("A"))
        assertEquals("PLAIN",index.terrainLegend['1'])
        assertFailsWith<UnsupportedOperationException> { (index.cellsOf("A") as MutableList<HanProvinceCell>).clear() }
        assertFailsWith<UnsupportedOperationException> { (index.terrainLegend as MutableMap<Char, String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (index.provinceIds as MutableSet<String>).clear() }
        for (bad in listOf(mapOf("A" to listOf(HanProvinceCell(0,0,'1'),HanProvinceCell(0,0,'1'))),
            mapOf("A" to listOf(HanProvinceCell(1,0,'1'),HanProvinceCell(0,0,'1'))),
            mapOf("A" to listOf(HanProvinceCell(2,0,'1'))),mapOf("A" to listOf(HanProvinceCell(0,0,'9'))),
            mapOf("A" to listOf(HanProvinceCell(0,0,'1')), "B" to listOf(HanProvinceCell(0,0,'1'))))) {
            assertFailsWith<IllegalArgumentException> {
                HanProvinceCellIndex("qa","a".repeat(64),"b".repeat(64),2,2,mapOf('1' to "PLAIN"),bad)
            }
        }
    }

    @Test fun `actual selected han bundle exposes a province cell inventory without current file fallback`() {
        val bundle = HanWorldArtifactsResolver(Path.of("..")).artifacts(HanWorldVariant.V3_1133)
        val bytes = bundle.artifactBytes(LandMarchMetricSnapshot.TILES_PATH)
        val index = HanProvinceCellJson.load(bundle.projection.topology,bytes)
        assertEquals(768,index.cols); assertEquals(669,index.rows)
        assertEquals(bundle.projection.topology.landProvinceIds,index.provinceIds)
        // Independently counted from this pinned source RLE, not a tactical-grid size choice.
        val cells = index.cellsOf("200012")
        assertEquals(103,cells.size)
        assertEquals(HanProvinceCell(448,138,'1'),cells.first())
        assertEquals(HanProvinceCell(449,146,'1'),cells.last())
        assertEquals(hash(bytes),index.tilesContentHash)
    }
}
