package opensamguk.gameapi.controller

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.seed.MapJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * W3 — [GetConstController] 슬라이스 테스트. DB/레포 주입이 전혀 없는 정적 상수 서비스이므로
 * MockMvc standalone만으로 검증한다(PHP `GetConst`는 NO_SESSION·DB-free 정적 API).
 *
 * 검증 포인트:
 *  - gameUnitConst가 `GameUnitConst.all()`의 모든 병종을 노출(첫 행 = 성벽 id 1000).
 *  - cityConst가 `CityConst.all()`의 94개 도시를 노출(id 1 = 업).
 *  - cityConstMap.region/level이 라벨→int 한 방향으로 노출(하북→1, 수→1).
     *  - iAction 키맵이 nationType 표시명/소개와 crewtype value를 노출.
 *  - gameConst 번들에 표시 상수가 담김(maxTurn 등).
 */
class GetConstControllerTest {

    private fun mockMvc(worlds: List<WorldStateReadEntity> = emptyList()): MockMvc {
        val worldRepo = mock(WorldStateReadRepository::class.java)
        `when`(worldRepo.findAll()).thenReturn(worlds)
        return MockMvcBuilders.standaloneSetup(GetConstController(worldRepo)).build()
    }

    @Test
    fun `const bundle exposes unit, city, cityConstMap, iAction, gameConst`() {
        mockMvc().perform(get("/api/const"))
            .andExpect(status().isOk)
            // gameUnitConst: 전체 병종 수 = GameUnitConst.all().size, 첫 행은 성벽(id 1000).
            .andExpect(jsonPath("$.gameUnitConst.length()").value(GameUnitConst.all().size))
            .andExpect(jsonPath("$.gameUnitConst[0].id").value(1000))
            .andExpect(jsonPath("$.gameUnitConst[0].name").value("성벽"))
            // cityConst: 94개 도시(che 정본), 첫 행은 id 1 업.
            .andExpect(jsonPath("$.cityConst.length()").value(CityConst.all().size))
            .andExpect(jsonPath("$.cityConst[0].id").value(1))
            .andExpect(jsonPath("$.cityConst[0].name").value("업"))
            // cityConstMap: 라벨→int 한 방향.
            .andExpect(jsonPath("$.cityConstMap.region.하북").value(1))
            .andExpect(jsonPath("$.cityConstMap.level.수").value(1))
            .andExpect(jsonPath("$.cityConstMap.level.소").value(5)) // lv5='소'(프로젝트 메모리)
            .andExpect(jsonPath("$.iAction.nationType[0].value").value(GameConst.availableNationType[0]))
            .andExpect(jsonPath("$.iAction.nationType[0].name").value("도적"))
            .andExpect(jsonPath("$.iAction.nationType[0].info[0]").value("계략↑ 금수입↓ 치안↓ 민심↓"))

            .andExpect(jsonPath("$.iAction.crewtype[0].value").value("1000")) // 첫 병종 id 문자열
            // gameConst 번들 표시 상수.
            .andExpect(jsonPath("$.gameConst.maxTurn").value(GameConst.maxTurn))
            .andExpect(jsonPath("$.gameConst.mapName").value(GameConst.mapName))
            .andExpect(jsonPath("$.gameConst.initialAllowedTechLevel").value(GameConst.initialAllowedTechLevel))
            .andExpect(jsonPath("$.gameConst.techLevelIncYear").value(GameConst.techLevelIncYear))
            .andExpect(jsonPath("$.gameConst.phasesPerMonth").value(GameConst.phasesPerMonth))
            .andExpect(jsonPath("$.gameConst.turnsPerYear").value(GameConst.turnsPerYear))
            .andExpect(jsonPath("$.gameConst.openingLimitTurns").value(GameConst.openingLimitTurns))
    }

    @Test
    fun `const bundle follows the active scenario map`() {
        val world = WorldStateReadEntity(
            id = 1,
            scenarioCode = "scenario_2",
            currentYear = 200,
            currentMonth = 1,
            config = mapOf("map" to mapOf("mapName" to "miniche_b")),
        )

        mockMvc(listOf(world)).perform(get("/api/const"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mapName").value("miniche_b"))
            .andExpect(jsonPath("$.mapWidth").value(1000))
            .andExpect(jsonPath("$.mapHeight").value(714))
            .andExpect(jsonPath("$.gameConst.mapName").value("miniche_b"))
            .andExpect(jsonPath("$.cityConst.length()").value(78))
            .andExpect(jsonPath("$.cityConst[0].id").value(1))
            .andExpect(jsonPath("$.cityConst[0].name").value("낙양"))
            .andExpect(jsonPath("$.cityConst[0].population").value(668600))
            .andExpect(jsonPath("$.cityConst[0].path['32']").value("하내"))
    }

    @Test
    fun `historical scenario exposes the han map without che fallback`() {
        val world = WorldStateReadEntity(
            id = 1,
            scenarioCode = "scenario_1010",
            currentYear = 184,
            currentMonth = 1,
            config = mapOf("mapName" to "han"),
        )

        mockMvc(listOf(world)).perform(get("/api/const"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mapName").value("han"))
            .andExpect(jsonPath("$.gameConst.mapName").value("han"))
            .andExpect(jsonPath("$.cityConst.length()").value(774))
            .andExpect(jsonPath("$.cityConst[0].name").value("장안"))
    }

    @Test
    fun `compatibility world exposes all 780 historical city constants`() {
        val world = WorldStateReadEntity(
            id = 1,
            scenarioCode = "scenario_1010",
            currentYear = 184,
            currentMonth = 1,
            config = mapOf("mapName" to "han-780-v1"),
        )

        mockMvc(listOf(world)).perform(get("/api/const"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mapName").value("han-780-v1"))
            .andExpect(jsonPath("$.cityConst.length()").value(780))
            .andExpect(jsonPath("$.cityConst[779].id").value(780))
    }

    @Test
    fun `seeded world without map metadata fails with its identity visible`() {
        val worldRepo = mock(WorldStateReadRepository::class.java)
        `when`(worldRepo.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 9,
                    scenarioCode = "scenario_broken",
                    config = emptyMap(),
                ),
            ),
        )

        val failure = assertThrows<IllegalStateException> {
            GetConstController(worldRepo).getConst()
        }

        assertTrue(failure.message.orEmpty().contains("id=9 scenario=scenario_broken has no active mapName"))
    }

    @Test
    fun `seeded world with unknown map metadata fails before resource lookup with its identity visible`() {
        val worldRepo = mock(WorldStateReadRepository::class.java)
        `when`(worldRepo.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 10,
                    scenarioCode = "scenario_unknown",
                    config = mapOf("mapName" to "unknown"),
                ),
            ),
        )

        val failure = assertThrows<IllegalStateException> {
            GetConstController(worldRepo).getConst()
        }

        assertTrue(failure.message.orEmpty().contains("id=10 scenario=scenario_unknown has invalid active mapName"))
        assertTrue(failure.message.orEmpty().contains("world state has unknown mapName: unknown"))
    }

    @Test
    fun `flat active mapName is canonical over the legacy nested fallback`() {
        val world = WorldStateReadEntity(
            id = 1,
            scenarioCode = "scenario_2",
            currentYear = 200,
            currentMonth = 1,
            config = mapOf(
                "map" to mapOf("mapName" to "che"),
                "mapName" to "miniche_b",
            ),
        )

        mockMvc(listOf(world)).perform(get("/api/const"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mapName").value("miniche_b"))
            .andExpect(jsonPath("$.cityConst[0].name").value("낙양"))
    }

    @Test
    fun `unsupported active unit set does not fall back to che content`() {
        val world = WorldStateReadEntity(
            id = 1,
            scenarioCode = "scenario_2",
            currentYear = 200,
            currentMonth = 1,
            config = mapOf(
                "mapName" to "che",
                "map" to mapOf("unitSet" to "che"),
                "unitSet" to "not-ported",
            ),
        )

        mockMvc(listOf(world)).perform(get("/api/const"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.gameConst.unitSet").value("not-ported"))
            .andExpect(jsonPath("$.gameUnitConst").isEmpty)
            .andExpect(jsonPath("$.iAction.crewtype").isEmpty)
    }

    @Test
    fun `a present blank unit set is unsupported instead of defaulting to che`() {
        val world = WorldStateReadEntity(
            id = 1,
            scenarioCode = "scenario_2",
            currentYear = 200,
            currentMonth = 1,
            config = mapOf("mapName" to "che", "unitSet" to "   "),
        )

        mockMvc(listOf(world)).perform(get("/api/const"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.gameConst.unitSet").value("   "))
            .andExpect(jsonPath("$.gameUnitConst").isEmpty)
            .andExpect(jsonPath("$.iAction.crewtype").isEmpty)
    }

    @Test
    fun `map dims come from the committed map resource, not controller literals`() {
        // 정본 = 커밋된 map/<GameConst.mapName>.json 리소스(MapJson 디코더, MapPreviewController와
        // 동일 로더). 컨트롤러 리터럴(1000/714 매직넘버) 금지 — 리소스가 바뀌면 응답도 따라가야 한다.
        val mapData = MapJson.loadFromClasspath(GameConst.mapName)
        require(mapData.width > 0 && mapData.height > 0) { "map/${GameConst.mapName}.json 리소스 부재" }
        mockMvc().perform(get("/api/const"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mapWidth").value(mapData.width))
            .andExpect(jsonPath("$.mapHeight").value(mapData.height))
    }

    @Test
    fun `officerLevelText serializes from the canonical F4StateText table`() {
        // 정본 = F4StateText.officerLevelText(PHP func_converter.php:522-565 getOfficerLevelText).
        // 와이어 모양 = hwe/ts/utilGame/formatOfficerLevelText.ts(OfficerLevelMapDefault +
        // OfficerLevelMapByNationLevel). 인라인 고정 맵(5→참모, 6→장군, 7→총사령관, 11→군주대리)은 위반.
        mockMvc().perform(get("/api/const"))
            .andExpect(status().isOk)
            // 기본열(nlevel=8) — PHP 805..812 + 공통 0..4.
            .andExpect(jsonPath("$.officerLevelText.12").value("군주"))
            .andExpect(jsonPath("$.officerLevelText.11").value("참모"))
            .andExpect(jsonPath("$.officerLevelText.10").value("제1장군"))
            .andExpect(jsonPath("$.officerLevelText.5").value("제3모사"))
            .andExpect(jsonPath("$.officerLevelText.4").value("태수"))
            .andExpect(jsonPath("$.officerLevelText.0").value("재야"))
            // 국가레벨별 수뇌 직책 — PHP 712 황제 / 612 왕 / 507 소부 / 12 두목.
            .andExpect(jsonPath("$.officerLevelTextByNationLevel.7.12").value("황제"))
            .andExpect(jsonPath("$.officerLevelTextByNationLevel.6.12").value("왕"))
            .andExpect(jsonPath("$.officerLevelTextByNationLevel.5.7").value("소부"))
            .andExpect(jsonPath("$.officerLevelTextByNationLevel.0.12").value("두목"))
            // PHP 미정의 코드(506 등)는 키 자체가 없어야 한다(PHP '-' — TS 폴백이 아니라 PHP가 이긴다).
            .andExpect(jsonPath("$.officerLevelTextByNationLevel.5.6").doesNotExist())
            .andExpect(jsonPath("$.officerLevelTextByNationLevel.1.10").doesNotExist())
    }

    @Test
    fun `nationType bundle includes neutral type at the tail`() {
        mockMvc().perform(get("/api/const"))
            .andExpect(status().isOk)
            // available + neutral 길이.
            .andExpect(
                jsonPath("$.iAction.nationType.length()")
                    .value(GameConst.availableNationType.size + 1),
            )
            // 마지막 항목 = 중립 타입.
            .andExpect(
                jsonPath("$.iAction.nationType[${GameConst.availableNationType.size}].value")
                    .value(GameConst.neutralNationType),
            )
    }
}
