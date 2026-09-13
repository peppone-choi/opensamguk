package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.HanWorldVariant
import org.junit.jupiter.api.Test
import kotlin.test.*

class HanWorldArtifactsResolverTest {
    private val resolver = HanWorldArtifactsResolver(Path.of("..").toAbsolutePath().normalize())

    @Test fun `complete old and current rosters select distinct verified bundles without persisted pins`() {
        val old = resolver.resolve((1..832).toList(), emptyList())
        val current = resolver.resolve((1..835).toList(), emptyList())
        assertEquals(HanWorldVariant.V3_832, old.variant)
        assertEquals(HanWorldVariant.V3_835, current.variant)
        assertNotEquals(old.projection.topology.contentHash, current.projection.topology.contentHash)
        assertEquals(old.cityConst.all().keys, old.projection.bindingsByCityId.keys)
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
