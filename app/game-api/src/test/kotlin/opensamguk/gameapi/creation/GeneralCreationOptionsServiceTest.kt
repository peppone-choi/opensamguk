package opensamguk.gameapi.creation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonInclude
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.ActiveWorldArtifactSnapshot
import opensamguk.gameapi.read.CityGeography
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.logic.world.WorldMapVariant
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class GeneralCreationOptionsServiceTest {
    companion object {
        private val bundle by lazy {
            WorldArtifactsResolver(Path.of("../..")).artifacts(WorldMapVariant.V3_1447_MAP4)
        }
    }

    private val resolver = mock(ActiveWorldArtifactResolver::class.java)
    private val geography = mock(CityGeography::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val service = GeneralCreationOptionsService(resolver, geography, generals,
        ObjectMapper(), GameApiProcessWorld(1))

    @Test fun `닫힌 세계는 선택 규칙을 보여도 본관과 생성 모드를 열지 않는다`() {
        val cityId = bundle.projection.administrativeCountyIds.first()
        val world = WorldStateReadEntity(id = 1, status = "CLOSED",
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "maxgeneral" to 50))
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
        assertTrue(720 in bundle.projection.administrativeCountyIds, "조선현은 외부 물리 장소에 결속된 행정 현")
        assertTrue(bundle.projection.bindingsByCityId[720]?.landProvinceId != null)
        assertTrue(833 in bundle.projection.administrativeCountyIds, "임융은 대리 治所에 선 행정 현")
        assertTrue(bundle.projection.bindingsByCityId[833]?.landProvinceId != null)
        val world = WorldStateReadEntity(id = 1, status = "OPEN", isunited = 0,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "maxgeneral" to 50))
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world,
            listOf(CityReadEntity(id = 1, worldId = 1, name = "장안현"),
                CityReadEntity(id = 704, worldId = 1, name = "구자속국"),
                CityReadEntity(id = 720, worldId = 1, name = "조선현"),
                CityReadEntity(id = 833, worldId = 1, name = "임융")), bundle))
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
        // 합성 관할 ID와 타일 ID가 달라도, 조선현의 검증된 물리 장소 X003은 정본 칸과 선택권을 갖는다.
        val external = options.nativeCounties.single { it.cityId == 720 }
        assertTrue(external.available)
        assertEquals(null, external.reason)
        assertEquals(2477, external.cellCol)
        assertEquals(569, external.cellRow)
        // 대리 治所의 省 cityIndex는 비어도 물리 X011 타일에는 정본 칸이 있다.
        val standInSeat = options.nativeCounties.single { it.cityId == 833 }
        assertTrue(standInSeat.available)
        assertEquals(null, standInSeat.reason)
        assertEquals(1121, standInSeat.cellCol)
        assertEquals(421, standInSeat.cellRow)
    }

    @Test fun `검증 중 생성 차단 설정은 열린 세계의 선택 정책도 닫는다`() {
        val world = WorldStateReadEntity(id = 1, status = "OPEN", isunited = 0,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "block_general_create" to 1,
                "maxgeneral" to 50))
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world,
            listOf(CityReadEntity(id = 1, worldId = 1, name = "장안현")), bundle))
        `when`(geography.places(bundle)).thenReturn(emptyMap())

        val options = service.options()
        assertFalse(options.policy.customAllowed)
        assertFalse(options.policy.historicalAllowed)
        assertEquals("CREATION_POLICY_UNAVAILABLE", options.policy.reason)
        assertTrue(options.modes.none { it.allowed })
        assertFalse(options.nativeCounties.single().available)
        assertEquals("CREATION_POLICY_UNAVAILABLE", options.nativeCounties.single().reason)
    }

    @Test fun `D121 역할 한도는 NON_NULL 직렬화에서도 명시적 null이고 전체 한도와 구분된다`() {
        val cityId = bundle.projection.administrativeCountyIds.first()
        val world = WorldStateReadEntity(id = 1, status = "OPEN", isunited = 0,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "maxgeneral" to 50))
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world,
            listOf(CityReadEntity(id = cityId, worldId = 1, name = "검증 현")), bundle))
        `when`(geography.places(bundle)).thenReturn(emptyMap())

        val mapper = ObjectMapper().findAndRegisterModules()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        val json = mapper.readTree(mapper.writeValueAsBytes(service.options()))
        assertEquals(50, json.path("playerCap").path("max").asInt(-1))
        assertEquals(0, json.path("playerCap").path("used").asInt(-1))
        val roles = json.path("roles")
        assertTrue(roles.isArray && roles.size() == 7)
        roles.forEach { role ->
            assertTrue(role.has("cap"), "명시적 null은 필드 부재와 다르다")
            assertTrue(role.get("cap").isNull, "전체 50을 역할 한도로 복사하면 안 된다")
            assertTrue(role.has("used") && role.get("used").isNull, "역할별 수를 추정하면 안 된다")
        }
        val retainer = roles.first { it.path("path").asText() == "CUSTOM" && it.path("role").asText() == "RETAINER" }
        assertTrue(retainer.path("allowed").asBoolean())
        val preLord = roles.first { it.path("path").asText() == "CUSTOM" && it.path("role").asText() == "PRE_LORD" }
        assertFalse(preLord.path("allowed").asBoolean())
        assertEquals("ROLE_UNAVAILABLE", preLord.path("reason").asText())
    }

    @Test fun `전체 정원 설정 또는 사용량 원천이 없으면 옵션을 열지 않는다`() {
        val cityId = bundle.projection.administrativeCountyIds.first()
        val cities = listOf(CityReadEntity(id = cityId, worldId = 1, name = "검증 현"))
        `when`(geography.places(bundle)).thenReturn(emptyMap())
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(
            WorldStateReadEntity(id = 1, status = "OPEN", isunited = 0,
                config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")), cities, bundle))
        assertFailsWith<CreationOptionsUnavailable> { service.options() }

        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(
            WorldStateReadEntity(id = 1, status = "OPEN", isunited = 0,
                config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "maxgeneral" to 50)),
            cities, bundle))
        doThrow(IllegalStateException("사용량 조회 불가")).`when`(generals).countByNpcStateLessThan(2)
        assertFailsWith<CreationOptionsUnavailable> { service.options() }
    }

    @Test fun `소수와 Int 범위 밖 정원은 표시 가능한 정원으로 잘라 쓰지 않는다`() {
        val cityId = bundle.projection.administrativeCountyIds.first()
        `when`(geography.places(bundle)).thenReturn(emptyMap())
        for (invalid in listOf(50.5, 4_294_967_346L)) {
            `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(
                WorldStateReadEntity(id = 1, status = "OPEN", isunited = 0,
                    config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "maxgeneral" to invalid)),
                listOf(CityReadEntity(id = cityId, worldId = 1, name = "검증 현")), bundle))
            assertFailsWith<CreationOptionsUnavailable>("maxgeneral=$invalid") { service.options() }
        }
    }

    @Test fun `전체 정원에 닿으면 모든 생성 길을 닫고 역할 cap은 무제한으로 남긴다`() {
        val cityId = bundle.projection.administrativeCountyIds.first()
        val world = WorldStateReadEntity(id = 1, status = "OPEN", isunited = 0,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "maxgeneral" to 50))
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world,
            listOf(CityReadEntity(id = cityId, worldId = 1, name = "검증 현")), bundle))
        `when`(geography.places(bundle)).thenReturn(emptyMap())
        `when`(generals.countByNpcStateLessThan(2)).thenReturn(50L)

        val options = service.options()
        assertEquals(50L, options.playerCap.used)
        assertEquals(50, options.playerCap.max)
        assertFalse(options.policy.customAllowed)
        assertFalse(options.policy.historicalAllowed)
        assertTrue(options.roles.none { it.allowed })
        assertTrue(options.roles.all { it.cap == null && it.used == null })
        assertFalse(options.nativeCounties.single().available)
    }
}
