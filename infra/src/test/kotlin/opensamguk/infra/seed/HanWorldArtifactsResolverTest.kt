package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.HanWorldVariant
import org.junit.jupiter.api.Test
import kotlin.test.*

class HanWorldArtifactsResolverTest {
    private val resolver = HanWorldArtifactsResolver(Path.of("..").toAbsolutePath().normalize())

    @Test fun `current generated release resolves complete identities and rejects historical pins`() {
        val mapPath = Path.of("src/main/resources/map/han-world-v3.json")
        val map = com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readAllBytes(mapPath))
        val ids = map.path("cities").map { it.path("id").asInt() }
        val selected = resolver.resolve(ids, emptyList())
        assertEquals(ids.toSet(), selected.cityConst.all().keys)
        assertEquals(ids.toSet(), selected.projection.bindingsByCityId.keys)
        assertContentEquals(java.nio.file.Files.readAllBytes(mapPath),
            selected.artifactBytes("infra/src/main/resources/map/han-world-v3.json"))
        for (path in listOf("data/map/han-scenario-province-ownership-v1.json",
            "data/map/han-scenario-jurisdiction-conflict-allowlist-v1.json",
            "data/map/han-commandery-supply-links-v1.json",
            "data/curated/han/territory-disconnection-adjudications-v1.json",
            "data/curated/han/supply-disconnection-adjudications-v3.json")) {
            assertTrue(selected.artifactBytes(path).isNotEmpty(), path)
        }
        val prior = resolver.resolve((1..835).toList(), emptyList()).projection.topology
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(ids, listOf(HanWorldTopologyPin("province_control", prior.topologyRevision, prior.contentHash)))
        }
    }

    @Test fun `complete old and current rosters select distinct verified bundles without persisted pins`() {
        val old = resolver.resolve((1..832).toList(), emptyList())
        val current = resolver.resolve((1..835).toList(), emptyList())
        assertEquals(HanWorldVariant.V3_832, old.variant)
        assertEquals(HanWorldVariant.V3_835, current.variant)
        assertNotEquals(old.projection.topology.contentHash, current.projection.topology.contentHash)
        assertEquals(old.cityConst.all().keys, old.projection.bindingsByCityId.keys)
    }

    @Test fun `frozen 846 and 848 rosters select distinct pinned bundles`() {
        val selected846 = resolver.resolve((1..846).toList(), emptyList())
        assertEquals(HanWorldVariant.V3_846, selected846.variant)
        val selected848 = resolver.resolve((1..848).toList(), emptyList())
        assertEquals(HanWorldVariant.V3_848, selected848.variant)
        assertEquals((1..846).toList(), selected846.cityConst.all().keys.sorted())
        assertEquals((1..848).toList(), selected848.cityConst.all().keys.sorted())
        assertNotEquals(
            selected846.artifactBytes("infra/src/main/resources/map/han-world-v3.json").toList(),
            selected848.artifactBytes("infra/src/main/resources/map/han-world-v3.json").toList(),
        )
    }

    @Test fun `same size altered identity partial and duplicate rosters fail closed`() {
        for (ids in listOf((2..833).toList(), (1..10).toList(), (1..832).toList() + 832)) {
            assertFailsWith<IllegalArgumentException> { resolver.resolve(ids, emptyList()) }
        }
    }

    @Test fun `every persisted pin must match the selected variant`() {
        val old = resolver.resolve((1..832).toList(), emptyList()).projection.topology
        val current = resolver.resolve((1..835).toList(), emptyList()).projection.topology
        val pins = listOf("water_zone_control", "province_control", "general_spatial_position").map {
            HanWorldTopologyPin(it, old.topologyRevision, old.contentHash)
        }
        assertEquals(HanWorldVariant.V3_832, resolver.resolve((1..832).toList(), pins).variant)
        for (index in pins.indices) {
            val mixed = pins.toMutableList()
            mixed[index] = pins[index].copy(hash = current.contentHash)
            assertFailsWith<IllegalArgumentException> { resolver.resolve((1..832).toList(), mixed) }
        }
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve((1..832).toList(), listOf(pins.first().copy(revision = null)))
        }
    }

    @Test fun `returned artifact bytes cannot mutate cached inputs`() {
        val bundle = resolver.resolve((1..832).toList(), emptyList())
        val path = "infra/src/main/resources/map/han-world-v3.json"
        val original = bundle.artifactBytes(path)
        bundle.artifactBytes(path).fill(0)
        assertContentEquals(original, bundle.artifactBytes(path))
    }
}
