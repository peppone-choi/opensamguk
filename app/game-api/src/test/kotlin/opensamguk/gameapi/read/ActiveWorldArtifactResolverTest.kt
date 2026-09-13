package opensamguk.gameapi.read

import java.nio.file.Path
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.infra.seed.HanWorldTopologyPin
import opensamguk.logic.world.HanWorldVariant
import org.mockito.Mockito.*
import kotlin.test.*

class ActiveWorldArtifactResolverTest {
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val pins = mock(WorldArtifactIdentityReadRepository::class.java)
    private val artifacts = HanWorldArtifactsResolver(Path.of("../.."))
    private val resolver = ActiveWorldArtifactResolver(worlds, cities, pins, artifacts)

    private fun roster(variant: HanWorldVariant) = artifacts.artifacts(variant).cityConst.all().keys.map {
        CityReadEntity(id = it, worldId = 8, name = "changed-$it")
    }

    @Test fun `reset changes selection and live labels without a cached world identity`() {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 8, config = mapOf("mapName" to "han-world-v3")))
        `when`(pins.readPins(8)).thenReturn(emptyList())
        for (variant in HanWorldVariant.entries) {
            `when`(cities.findAll()).thenReturn(roster(variant))
            val selected = assertNotNull(resolver.resolve())
            assertEquals(variant, selected.artifacts!!.variant)
            val expectedNames = opensamguk.infra.seed.MapJson.loadMap(
                selected.artifacts.artifactBytes("infra/src/main/resources/map/han-world-v3.json").toString(Charsets.UTF_8)
            ).cities.mapNotNull { city -> city.nameCh?.let { city.id to it } }.toMap()
            assertEquals(expectedNames, resolver.cityNames())
            assertEquals("changed-1", selected.cities.first().name)
        }
    }

    @Test fun `partial roster foreign city and wrong stored pin are rejected`() {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 8, config = mapOf("mapName" to "han-world-v3")))
        `when`(pins.readPins(8)).thenReturn(emptyList())
        `when`(cities.findAll()).thenReturn(roster(HanWorldVariant.V3_832).take(2))
        assertFailsWith<IllegalArgumentException> { resolver.resolve() }
        `when`(cities.findAll()).thenReturn(roster(HanWorldVariant.V3_832).onEach { it.worldId = 9 })
        assertFailsWith<IllegalArgumentException> { resolver.resolve() }
        `when`(cities.findAll()).thenReturn(roster(HanWorldVariant.V3_832))
        `when`(pins.readPins(8)).thenReturn(listOf(HanWorldTopologyPin("province_control", "wrong", "wrong")))
        assertFailsWith<IllegalArgumentException> { resolver.resolve() }
    }

    @Test fun `unseeded and legacy worlds do not query Han spatial pins`() {
        `when`(worlds.findProcessWorld()).thenReturn(null)
        assertNull(resolver.resolve())
        verifyNoInteractions(cities, pins)
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 8, config = mapOf("mapName" to "che")))
        `when`(cities.findAll()).thenReturn(emptyList())
        assertNull(resolver.resolve()!!.artifacts)
        verifyNoInteractions(pins)
    }
}
