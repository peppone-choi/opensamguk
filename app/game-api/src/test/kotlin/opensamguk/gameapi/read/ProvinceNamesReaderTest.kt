package opensamguk.gameapi.read

import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.infra.seed.WorldTopologyPin
import opensamguk.logic.world.StrategicRouteProjection
import opensamguk.logic.world.StrategicTopologySnapshot
import opensamguk.logic.world.WorldMapVariant
import org.mockito.Mockito.*
import java.security.MessageDigest
import kotlin.test.*

class ProvinceNamesReaderTest {
    private val states = mock(WorldStateReadRepository::class.java)
    private val worlds = mock(ActiveWorldArtifactResolver::class.java)
    private val pins = mock(WorldArtifactIdentityReadRepository::class.java)
    private val reader = ProvinceNamesReader(states, worlds, pins)
    private val bytes = """{"provinceRecords":[{"id":"P1","displayName":"한구역"}]}""".toByteArray()
    private val world = WorldStateReadEntity(id = 7, config = mapOf("mapName" to "han-world-v3"))
    private val hash = "b".repeat(64)

    private fun setup() {
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        val projection = mock(StrategicRouteProjection::class.java)
        val topology = mock(StrategicTopologySnapshot::class.java)
        `when`(states.findProcessWorld()).thenReturn(world)
        `when`(pins.readPins(7)).thenReturn(listOf(WorldTopologyPin("province_control", "synthetic", hash)))
        `when`(worlds.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        `when`(bundle.variant).thenReturn(WorldMapVariant.V3_1428)
        `when`(bundle.projection).thenReturn(projection)
        `when`(projection.topology).thenReturn(topology)
        `when`(topology.contentHash).thenReturn(hash)
        `when`(topology.topologyRevision).thenReturn("synthetic")
        `when`(topology.landProvinceIds).thenReturn(setOf("P1"))
        `when`(topology.artifactHashes).thenReturn(mapOf(ProvinceNamesCache.TILES_PATH to
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
        `when`(bundle.artifactBytes(ProvinceNamesCache.TILES_PATH)).thenReturn(bytes)
    }

    @Test fun `missing world returns unavailable without consulting artifacts or cache`() {
        assertNull(reader.current())
        verifyNoInteractions(worlds, pins)
    }

    @Test fun `stored pins are required before cold artifact selection and fail closed after cache hit`() {
        setup()
        assertEquals("한구역", reader.current()!!.dto.names.single().displayName)
        `when`(pins.readPins(7)).thenReturn(emptyList())
        clearInvocations(worlds)
        assertFailsWith<IllegalArgumentException> { reader.current() }
        verifyNoInteractions(worlds)
    }

    @Test fun `changed persisted revision or hash never serves a previously cached response`() {
        setup()
        reader.current()
        for (pin in listOf(WorldTopologyPin("province_control", "changed", hash),
            WorldTopologyPin("province_control", "synthetic", "c".repeat(64)))) {
            `when`(pins.readPins(7)).thenReturn(listOf(pin))
            assertFailsWith<IllegalArgumentException> { reader.current() }
        }
        verify(pins, times(3)).readPins(7)
    }

    @Test fun `every request reselects world and rejects cross-world artifacts`() {
        setup()
        reader.current(); reader.current()
        verify(states, times(2)).findProcessWorld()
        verify(worlds, times(2)).resolve()
        val selected = worlds.resolve()!!
        `when`(worlds.resolve()).thenReturn(selected.copy(world = WorldStateReadEntity(id = 8)))
        assertFailsWith<IllegalArgumentException> { reader.current() }
    }
}
