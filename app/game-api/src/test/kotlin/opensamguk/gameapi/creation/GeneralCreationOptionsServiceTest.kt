package opensamguk.gameapi.creation

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.ActiveWorldArtifactSnapshot
import opensamguk.gameapi.read.CityGeography
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.logic.world.WorldMapVariant
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneralCreationOptionsServiceTest {
    private val resolver = mock(ActiveWorldArtifactResolver::class.java)
    private val geography = mock(CityGeography::class.java)
    private val service = GeneralCreationOptionsService(resolver, geography, GameApiProcessWorld(1))

    @Test fun `닫힌 세계는 선택 규칙을 보여도 본관과 생성 모드를 열지 않는다`() {
        val bundle = WorldArtifactsResolver(Path.of("../..")).artifacts(WorldMapVariant.V3_1447_MAP4)
        val cityId = bundle.projection.administrativeCountyIds.first()
        val world = WorldStateReadEntity(id = 1, status = "CLOSED",
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"))
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world,
            listOf(CityReadEntity(id = cityId, worldId = 1, name = "검증 현")), bundle))
        `when`(geography.places(bundle)).thenReturn(emptyMap())

        val options = service.options()
        assertEquals(1, options.worldId)
        assertEquals(300, options.statRule.total)
        assertEquals(6, options.ideologies.size)
        assertEquals(6, options.traits.size)
        assertFalse(options.policy.customAllowed)
        assertFalse(options.policy.historicalAllowed)
        assertTrue(options.modes.none { it.allowed })
        assertEquals("CREATION_POLICY_UNAVAILABLE", options.nativeCounties.single().reason)
    }
}
