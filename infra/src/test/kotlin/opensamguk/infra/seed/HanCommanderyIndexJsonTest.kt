package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import kotlin.test.*
import opensamguk.logic.world.HanWorldVariant
import opensamguk.logic.world.LandMarchMetricSnapshot

/** The vision commandery number must be the provinces PNG commandery channel for every released map. */
class HanCommanderyIndexJsonTest {
    private val resolver = HanWorldArtifactsResolver(Path.of(".."))
    private val mapper = ObjectMapper()

    @Test fun `every released han variant yields an index aligned with juns and the declared adjacency`() {
        val variants = HanWorldVariant.entries.filter { it.cityCount >= 846 }
        assertTrue(variants.size >= 8)
        for (variant in variants) {
            val bundle = resolver.artifacts(variant)
            val index = bundle.commanderyIndex
            val tiles = mapper.readTree(bundle.artifactBytes(LandMarchMetricSnapshot.TILES_PATH))
            val juns = tiles.path("juns")
            assertEquals(juns.size(), index.commanderies.size, variant.name)
            index.commanderies.forEach { assertEquals(juns[it.no].path("name").asText(), it.name, "${variant.name} #${it.no}") }
            assertEquals(bundle.projection.topology.landProvinceIds, index.provinceIds, variant.name)
            val declared = tiles.path("adjacency").path("commandery").map { minOf(it["a"].asInt(), it["b"].asInt()) to maxOf(it["a"].asInt(), it["b"].asInt()) }.toSet()
            val derived = index.commanderies.flatMap { c -> index.neighbours(c.no).filter { it > c.no }.map { c.no to it } }.toSet()
            assertEquals(declared, derived, variant.name)
            // Provinces keep their tile parent: a spot check through the raw records.
            tiles.path("provinceRecords").take(50).forEach { record ->
                val parent = tiles.path("parentRegions").indexOfFirst { it["id"].asText() == record["parentRegionId"].asText() }
                assertEquals(parent, index.commanderyOf(record["id"].asText()))
            }
        }
    }

    @Test fun `the 1168 map places 하남윤 next to its five neighbours`() {
        val index = resolver.artifacts(HanWorldVariant.V3_1168).commanderyIndex
        val henan = index.commanderies.single { it.nameCh == "河南尹" }
        assertEquals(0, henan.no)
        assertEquals(setOf("영천군", "진류군", "하내군", "하동군", "홍농군"), index.neighbours(henan.no).map { index.commanderies[it].name }.toSet())
    }

    // ── red probes: each cross-check must fire on a tampered copy ──────────

    private fun tampered(edit: (ObjectNode) -> Unit): Result<*> {
        val bundle = resolver.artifacts(HanWorldVariant.V3_1168)
        val root = mapper.readTree(bundle.artifactBytes(LandMarchMetricSnapshot.TILES_PATH)) as ObjectNode
        edit(root)
        return runCatching { HanCommanderyIndexJson.parse(mapper.writeValueAsBytes(root),
            bundle.projection.topology.landProvinceIds, "f".repeat(64)) }
    }

    @Test fun `declared adjacency that disagrees with the raster is rejected`() {
        assertTrue(tampered { }.isSuccess)
        val result = tampered { root -> (root.path("adjacency").path("commandery") as ArrayNode).remove(0) }
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test fun `juns that name a different commandery than parentRegions are rejected`() {
        val result = tampered { root -> (root.path("juns")[3] as ObjectNode).put("nameCh", "不存在郡") }
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test fun `a province with an unknown parent is rejected`() {
        val result = tampered { root -> (root.path("provinceRecords")[0] as ObjectNode).put("parentRegionId", "PARENT-9999") }
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test fun `bytes that differ from the topology pin are rejected before parsing`() {
        val bundle = resolver.artifacts(HanWorldVariant.V3_1168)
        val bytes = bundle.artifactBytes(LandMarchMetricSnapshot.TILES_PATH) + " ".toByteArray()
        assertFailsWith<IllegalArgumentException> { HanCommanderyIndexJson.load(bundle.projection.topology, bytes) }
    }
}
