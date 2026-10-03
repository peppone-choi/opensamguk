package opensamguk.infra.seed

import java.nio.file.Files
import java.nio.file.Path
import opensamguk.logic.world.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ProvinceWorldArtifactsIntegrityTest {
    @TempDir lateinit var temporary: Path
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test fun `neutral release preserves physical rules and stored archive selection`() {
        val resolver = WorldArtifactsResolver(root)
        val old = resolver.artifacts(WorldMapVariant.V3_1428)
        val current = resolver.artifacts(WorldMapVariant.PROVINCE_WORLD)
        assertEquals(old.cityConst.all(), current.cityConst.all())
        assertEquals(old.projection.bindingsByCityId, current.projection.bindingsByCityId)
        assertEquals(old.projection.topology.landProvinceIds, current.projection.topology.landProvinceIds)
        assertEquals(old.projection.topology.waterZones, current.projection.topology.waterZones)
        assertEquals(old.projection.topology.riverBarriers, current.projection.topology.riverBarriers)
        assertEquals(old.projection.topology.traversalEdges.map { it.copy(sourceRefs = listOf("physical-comparison")) },
                     current.projection.topology.traversalEdges.map { it.copy(sourceRefs = listOf("physical-comparison")) })
        assertEquals(old.landMarchMetrics.edgesById, current.landMarchMetrics.edgesById)
        assertContentEquals(old.artifactBytes(MapArtifactContract.CURRENT.tilesPath),
                            current.artifactBytes(MapArtifactContract.CURRENT.tilesPath))
        assertNotEquals(old.projection.topology.contentHash, current.projection.topology.contentHash)
        val ids = old.cityConst.all().keys.toList()
        assertEquals(WorldMapVariant.V3_1428, resolver.resolve(ids, emptyList()).variant)
        for (release in listOf(old, current)) {
            val topology = release.projection.topology
            val pins = listOf("province_control", "water_zone_control", "general_spatial_position").map {
                WorldTopologyPin(it, topology.topologyRevision, topology.contentHash)
            }
            assertEquals(release.variant, resolver.resolve(ids, pins).variant)
            assertFailsWith<IllegalArgumentException> { resolver.resolve(ids, pins.map { it.copy(hash = "0".repeat(64)) }) }
            assertFailsWith<IllegalArgumentException> { resolver.resolve(ids, pins + pins.first().copy(hash = "0".repeat(64))) }
        }
    }

    @Test fun `missing and corrupt neutral release inputs cannot fall back to current checkout`() {
        val relative = "data/map/province-world-20261003-artifacts"
        val source = root.resolve(relative)
        val catalog = Files.readAllBytes(source.resolve("catalog.json"))
        val blob = com.fasterxml.jackson.databind.ObjectMapper().readTree(catalog).path("files").first().path("blob").asText()
        for (mutation in listOf("catalog", "blob", "missing")) {
            val destination = temporary.resolve(mutation).resolve(relative)
            Files.walk(source).use { paths -> paths.forEach { path ->
                val target = destination.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            val selectedRoot = temporary.resolve(mutation)
            ProvinceWorldArtifacts.load(selectedRoot)
            when (mutation) {
                "catalog" -> Files.write(destination.resolve("catalog.json"), catalog + byteArrayOf(10))
                "blob" -> Files.write(destination.resolve(blob), byteArrayOf(0, 1, 2))
                "missing" -> Files.delete(destination.resolve(blob))
            }
            assertFails { ProvinceWorldArtifacts.load(selectedRoot) }
        }
    }

    @Test fun `current terrain contract rejects duplicate historical and neutral hash channels`() {
        val current = WorldArtifactsResolver(root).artifacts(WorldMapVariant.PROVINCE_WORLD).projection.topology
        assertTrue(MapArtifactContract.CURRENT.tilesPath in current.artifactHashes)
        assertFalse(MapArtifactContract.ARCHIVE.tilesPath in current.artifactHashes)
        val mixed = StrategicTopologySnapshot(current.topologyRevision, current.landProvinceIds, current.waterZones,
            current.traversalEdges, current.riverBarriers,
            current.artifactHashes + (MapArtifactContract.ARCHIVE.tilesPath to current.tilesArtifactHash()))
        assertFailsWith<IllegalArgumentException> { mixed.tilesArtifactHash() }
    }
}
