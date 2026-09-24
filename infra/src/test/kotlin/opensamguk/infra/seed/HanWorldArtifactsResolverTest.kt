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
        val selected = resolver.artifacts(HanWorldVariant.V3_1447_MAP4)
        assertEquals(ids.toSet(), selected.cityConst.all().keys)
        assertEquals(ids.toSet(), selected.projection.bindingsByCityId.keys)
        val roadGates = requireNotNull(selected.projection.presentation).roadGates
        val topologyRoads = selected.projection.topology.traversalEdges.associateBy { it.id }
        assertEquals(4284, roadGates.size)
        assertEquals(656, roadGates.count { it.overviewTrunk })
        assertEquals(12, roadGates.count { !it.buildable })
        assertTrue(roadGates.all { gate -> topologyRoads.getValue(gate.edgeId).initiallyOpen == gate.initiallyBuilt })
        assertTrue(roadGates.filterNot { it.buildable }.none { it.initiallyBuilt })
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
        val currentPin = selected.projection.topology.let {
            HanWorldTopologyPin("province_control", it.topologyRevision, it.contentHash)
        }
        assertEquals(HanWorldVariant.V3_1447_MAP4, resolver.resolve(ids, listOf(currentPin)).variant)
        assertEquals(HanWorldVariant.V3_1447, resolver.resolve(ids, emptyList()).variant)
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

    @Test fun `1098 roster selects its own pinned bundle with strategic-site provinces`() {
        val selected = resolver.resolve((1..1098).toList(), emptyList())
        assertEquals(HanWorldVariant.V3_1098, selected.variant)
        assertEquals((1..1098).toList(), selected.cityConst.all().keys.sorted())
        assertEquals(1594, selected.projection.topology.landProvinceIds.size)
        assertNotEquals(
            resolver.resolve((1..848).toList(), emptyList()).projection.topology.contentHash,
            selected.projection.topology.contentHash,
        )
    }

    @Test fun `1133 roster selects its own pinned bundle where every province has a city jurisdiction`() {
        val selected = resolver.resolve((1..1133).toList(), emptyList())
        assertEquals(HanWorldVariant.V3_1133, selected.variant)
        assertEquals((1..1133).toList(), selected.cityConst.all().keys.sorted())
        // 2026-09-18 지리 재분할(GH #806, ADR-LITE-063): 1133 번들을 제자리 재핀 — 1,594 → 1,331.
        assertEquals(1331, selected.projection.topology.landProvinceIds.size)
        assertNotEquals(
            resolver.resolve((1..1098).toList(), emptyList()).projection.topology.contentHash,
            selected.projection.topology.contentHash,
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
