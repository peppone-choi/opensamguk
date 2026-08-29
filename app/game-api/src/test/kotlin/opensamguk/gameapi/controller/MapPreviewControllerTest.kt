package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.NationEnvReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.entity.NationEnvEntity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertTrue

/**
 * Lightweight slice test for [MapPreviewController] — MockMvc standalone setup over mocked read repos
 * (NO Spring context, NO Testcontainers). Asserts the exact JSON contract the gateway client is built
 * against, the city-coord merge from `scenario/cities_1010.json`, neutral-nation exclusion, and the
 * empty-world 200 path.
 */
class MapPreviewControllerTest {

    private val cityRepo = mock(CityReadRepository::class.java)
    private val nationRepo = mock(NationReadRepository::class.java)
    private val worldRepo = mock(WorldStateReadRepository::class.java)
    private val nationEnv = mock(NationEnvReadRepository::class.java)
    private val objectMapper = ObjectMapper()

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(MapPreviewController(cityRepo, nationRepo, worldRepo, nationEnv, objectMapper)).build()

    private fun city(id: Int, level: Int, nationId: Int, region: Int = 0) =
        CityReadEntity(id = id, nationId = nationId, level = level, region = region)

    private fun nation(id: Int, name: String, color: String) =
        NationReadEntity(id = id, name = name, color = color)

    @Test
    fun `returns the world-map snapshot with merged coords and contract shape`() {
        // 좌표 원천 = map/che.json(php 정본 native 700×500). id1 업(345,130) id3 낙양(275,180).
        // id 999 absent → omitted. (Double 직렬화라 345 → 345.0)
        `when`(worldRepo.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "che_1010",
                    currentYear = 200,
                    currentMonth = 3,
                    config = mapOf("mapName" to "che"),
                ),
            )
        )
        `when`(cityRepo.findAll()).thenReturn(
            listOf(
                city(id = 3, level = 8, nationId = 1, region = 2), // 중원
                city(id = 1, level = 8, nationId = 2, region = 1), // 하북
                city(id = 999, level = 5, nationId = 0), // no coord in JSON → dropped
            )
        )
        `when`(nationRepo.findAll()).thenReturn(
            listOf(nation(id = 1, name = "위", color = "#c62828"), nation(id = 0, name = "재야", color = "#000000"))
        )

        mockMvc().perform(get("/api/map/preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.serverName").value("che_1010"))
            .andExpect(jsonPath("$.year").value(200))
            .andExpect(jsonPath("$.month").value(3))
            .andExpect(jsonPath("$.mapCode").value("che"))
            .andExpect(jsonPath("$.width").value(700))
            .andExpect(jsonPath("$.height").value(500))
            // id 999 (no coord) dropped → 2 cities, sorted by id (1 then 3)
            .andExpect(jsonPath("$.cities.length()").value(2))
            .andExpect(jsonPath("$.cities[0].id").value(1))
            .andExpect(jsonPath("$.cities[0].name").value("업"))
            .andExpect(jsonPath("$.cities[0].x").value(345.0))
            .andExpect(jsonPath("$.cities[0].y").value(130.0))
            .andExpect(jsonPath("$.cities[0].region").value(1)) // W3 — city.region(하북)
            // city id 3 → 낙양 with merged coords
            .andExpect(jsonPath("$.cities[1].id").value(3))
            .andExpect(jsonPath("$.cities[1].name").value("낙양"))
            .andExpect(jsonPath("$.cities[1].level").value(8))
            .andExpect(jsonPath("$.cities[1].nationId").value(1))
            .andExpect(jsonPath("$.cities[1].x").value(275.0))
            .andExpect(jsonPath("$.cities[1].y").value(180.0))
            // neutral nation 0 excluded; only nation 1 present
            .andExpect(jsonPath("$.nations.length()").value(1))
            .andExpect(jsonPath("$.nations[0].id").value(1))
            .andExpect(jsonPath("$.nations[0].color").value("#c62828"))
    }

    @Test
    fun `uses the scenario mapName from world config`() {
        `when`(worldRepo.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "scenario_2",
                    currentYear = 200,
                    currentMonth = 3,
                    currentPhase = 3,
                    config = mapOf("map" to mapOf("mapName" to "miniche_b")),
                ),
            ),
        )
        `when`(cityRepo.findAll()).thenReturn(listOf(city(id = 1, level = 8, nationId = 1, region = 2)))
        `when`(nationRepo.findAll()).thenReturn(listOf(nation(id = 1, name = "위", color = "#c62828")))

        mockMvc().perform(get("/api/map/preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mapCode").value("miniche_b"))
            .andExpect(jsonPath("$.width").value(1000))
            .andExpect(jsonPath("$.height").value(714))
            .andExpect(jsonPath("$.turnPhase").value(3))
            .andExpect(jsonPath("$.turnPhaseText").value("하순"))
            .andExpect(jsonPath("$.cities[0].name").value("낙양"))
            .andExpect(jsonPath("$.cities[0].x").value(407.14))
            .andExpect(jsonPath("$.cities[0].y").value(251.43))
    }

    @Test
    fun `uses the flat han mapName written by the scenario importer`() {
        `when`(worldRepo.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "scenario_1010",
                    currentYear = 184,
                    currentMonth = 1,
                    config = mapOf("mapName" to "han"),
                ),
            ),
        )
        `when`(cityRepo.findAll()).thenReturn(listOf(city(id = 1, level = 9, nationId = 1, region = 1)))
        `when`(nationRepo.findAll()).thenReturn(emptyList())

        mockMvc().perform(get("/api/map/preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mapCode").value("han"))
            .andExpect(jsonPath("$.width").value(700))
            .andExpect(jsonPath("$.height").value(610))
            .andExpect(jsonPath("$.cities[0].regionName").value("사예"))
            .andExpect(jsonPath("$.cities[0].commanderyName").value("경조윤"))
            .andExpect(jsonPath("$.cities[0].isCommanderySeat").value(true))
    }

    @Test
    fun `compatibility world preview exposes all 780 historical coordinates`() {
        `when`(worldRepo.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "scenario_1010",
                    currentYear = 184,
                    currentMonth = 1,
                    config = mapOf("mapName" to "han-780-v1"),
                ),
            ),
        )
        `when`(cityRepo.findAll()).thenReturn((1..780).map { city(id = it, level = 5, nationId = 0) })
        `when`(nationRepo.findAll()).thenReturn(emptyList())

        mockMvc().perform(get("/api/map/preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mapCode").value("han-780-v1"))
            .andExpect(jsonPath("$.cities.length()").value(780))
            .andExpect(jsonPath("$.cities[779].id").value(780))
    }

    @Test
    fun `seeded preview without map metadata fails visibly`() {
        `when`(worldRepo.findAll()).thenReturn(
            listOf(WorldStateReadEntity(id = 4, scenarioCode = "scenario_broken")),
        )

        val failure = assertThrows<IllegalStateException> {
            MapPreviewController(cityRepo, nationRepo, worldRepo, nationEnv, objectMapper).preview()
        }

        assertTrue(failure.message.orEmpty().contains("id=4 scenario=scenario_broken has no active mapName"))
    }

    @Test
    fun `surfaces city state disaster code not front_state`() {
        // 재해/사건 코드 — func_map.php tuple state자리 = city.state, front_state 아님.
        // front_state=1(전선)과 state=7(재해코드)를 서로 다르게 둬 노출 컬럼을 분리 검증.
        `when`(worldRepo.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "che_1010",
                    currentYear = 200,
                    currentMonth = 3,
                    config = mapOf("mapName" to "che"),
                ),
            ),
        )
        `when`(cityRepo.findAll()).thenReturn(
            listOf(CityReadEntity(id = 3, nationId = 1, level = 8, region = 2, frontState = 1, state = 7)), // 낙양
        )
        `when`(nationRepo.findAll()).thenReturn(
            listOf(nation(id = 1, name = "위", color = "#c62828")),
        )

        mockMvc().perform(get("/api/map/preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cities.length()").value(1))
            .andExpect(jsonPath("$.cities[0].id").value(3))
            // state = city.state(재해/사건 코드 7), NOT front_state(1)
            .andExpect(jsonPath("$.cities[0].state").value(7))
    }

    @Test
    fun `empty world returns 200 with empty cities and nations and zero clock`() {
        `when`(worldRepo.findAll()).thenReturn(emptyList())

        mockMvc().perform(get("/api/map/preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.year").value(0))
            .andExpect(jsonPath("$.month").value(0))
            .andExpect(jsonPath("$.mapCode").value("che"))
            .andExpect(jsonPath("$.cities.length()").value(0))
            .andExpect(jsonPath("$.nations.length()").value(0))
    }

    // ── W0-2(P1-060) startYear — legacy 맵 페이로드 필드(func_map.php:68,158) ────────────────────────

    @Test
    fun `exposes startYear from the world config when seeded`() {
        `when`(worldRepo.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1, scenarioCode = "che_1010", currentYear = 200, currentMonth = 3,
                    config = mapOf("startyear" to 180, "mapName" to "che"),
                ),
            ),
        )
        `when`(cityRepo.findAll()).thenReturn(emptyList())
        `when`(nationRepo.findAll()).thenReturn(emptyList())

        mockMvc().perform(get("/api/map/preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.startYear").value(180))
    }

    @Test
    fun `preview nations expose join scout message and scenario info text`() {
        `when`(worldRepo.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "che_1010",
                    currentYear = 200,
                    currentMonth = 3,
                    config = mapOf("mapName" to "che"),
                ),
            ),
        )
        `when`(cityRepo.findAll()).thenReturn(emptyList())
        `when`(nationRepo.findAll()).thenReturn(
            listOf(
                NationReadEntity(
                    id = 1,
                    name = "위",
                    color = "#c62828",
                    meta = mapOf("infoText" to "천하를 도모합니다."),
                ),
            ),
        )
        `when`(nationEnv.findByNamespaceAndKey(1, "scout_msg")).thenReturn(
            NationEnvEntity(namespace = 1, key = "scout_msg", value = "\"인재를 구합니다\""),
        )

        mockMvc().perform(get("/api/map/preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nations[0].scoutMsg").value("인재를 구합니다"))
            .andExpect(jsonPath("$.nations[0].infoText").value("천하를 도모합니다."))
    }

    @Test
    fun `startYear is null when the config does not carry it`() {
        `when`(worldRepo.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "che_1010",
                    currentYear = 200,
                    currentMonth = 3,
                    meta = mapOf("mapName" to "che"),
                ),
            ),
        )
        `when`(cityRepo.findAll()).thenReturn(emptyList())
        `when`(nationRepo.findAll()).thenReturn(emptyList())

        mockMvc().perform(get("/api/map/preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.startYear").doesNotExist())
    }
}
