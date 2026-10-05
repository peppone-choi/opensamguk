package opensamguk.gameapi.creation

import com.fasterxml.jackson.databind.ObjectMapper
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
    companion object {
        private val bundle by lazy {
            WorldArtifactsResolver(Path.of("../..")).artifacts(WorldMapVariant.V3_1447_MAP4)
        }
    }

    private val resolver = mock(ActiveWorldArtifactResolver::class.java)
    private val geography = mock(CityGeography::class.java)
    private val service = GeneralCreationOptionsService(resolver, geography, ObjectMapper(), GameApiProcessWorld(1))

    @Test fun `닫힌 세계는 선택 규칙을 보여도 본관과 생성 모드를 열지 않는다`() {
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

    @Test fun `열린 세계는 행정 현의 정본 칸만 선택 가능하게 낸다`() {
        assertTrue(1 in bundle.projection.administrativeCountyIds, "장안현은 행정 현")
        assertFalse(704 in bundle.projection.administrativeCountyIds, "구자속국은 비행정 외부 거점")
        assertFalse(720 in bundle.projection.administrativeCountyIds, "외부 거점은 본관 현이 아님")
        val world = WorldStateReadEntity(id = 1, status = "OPEN", isunited = 0,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"))
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world,
            listOf(CityReadEntity(id = 1, worldId = 1, name = "장안현"),
                CityReadEntity(id = 704, worldId = 1, name = "구자속국"),
                CityReadEntity(id = 720, worldId = 1, name = "외부 거점")), bundle))
        `when`(geography.places(bundle)).thenReturn(emptyMap())

        val options = service.options()
        assertTrue(options.policy.customAllowed)
        assertTrue(options.policy.historicalAllowed)
        val native = options.nativeCounties.single { it.cityId == 1 }
        assertTrue(native.available)
        assertEquals(null, native.reason)
        // 고정 map4 han-tiles: provinceRecords[503].cityIndex -> cities[1033] = (1233, 969).
        // 런타임 지도 x/y=(281, 221)로 바꾸면 이 단언이 실패해야 한다.
        assertEquals(1233, native.cellCol)
        assertEquals(969, native.cellRow)
        val nonCounty = options.nativeCounties.single { it.cityId == 704 }
        assertFalse(nonCounty.available)
        assertEquals("INVALID_NATIVE_COUNTY", nonCounty.reason)
        assertEquals(null, nonCounty.cellCol)
        assertEquals(null, nonCounty.cellRow)
        // Synthetic jurisdiction IDs differ; the verified external physical place X003 has a tile.
        val external = options.nativeCounties.single { it.cityId == 720 }
        assertFalse(external.available)
        assertEquals("INVALID_NATIVE_COUNTY", external.reason)
        assertEquals(2477, external.cellCol)
        assertEquals(569, external.cellRow)
    }
}
