package opensamguk.gameapi.controller

import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

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

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(MapPreviewController(cityRepo, nationRepo, worldRepo)).build()

    private fun city(id: Int, level: Int, nationId: Int) =
        CityReadEntity(id = id, nationId = nationId, level = level)

    private fun nation(id: Int, name: String, color: String) =
        NationReadEntity(id = id, name = name, color = color)

    @Test
    fun `returns the world-map snapshot with merged coords and contract shape`() {
        // 좌표 원천 = map/che.json(TS 레거시 ×10/7 비례 확대). native: id1 업(345,130) id3 낙양(275,180)
        // → 표시: id1(492.86,185.71) id3(392.86,257.14). id 999 absent → omitted.
        `when`(worldRepo.findAll()).thenReturn(
            listOf(WorldStateReadEntity(id = 1, scenarioCode = "che_1010", currentYear = 200, currentMonth = 3))
        )
        `when`(cityRepo.findAll()).thenReturn(
            listOf(
                city(id = 3, level = 8, nationId = 1),
                city(id = 1, level = 8, nationId = 2),
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
            .andExpect(jsonPath("$.width").value(1000))
            .andExpect(jsonPath("$.height").value(714))
            // id 999 (no coord) dropped → 2 cities, sorted by id (1 then 3)
            .andExpect(jsonPath("$.cities.length()").value(2))
            .andExpect(jsonPath("$.cities[0].id").value(1))
            .andExpect(jsonPath("$.cities[0].name").value("업"))
            .andExpect(jsonPath("$.cities[0].x").value(492.86))
            .andExpect(jsonPath("$.cities[0].y").value(185.71))
            // city id 3 → 낙양 with merged coords
            .andExpect(jsonPath("$.cities[1].id").value(3))
            .andExpect(jsonPath("$.cities[1].name").value("낙양"))
            .andExpect(jsonPath("$.cities[1].level").value(8))
            .andExpect(jsonPath("$.cities[1].nationId").value(1))
            .andExpect(jsonPath("$.cities[1].x").value(392.86))
            .andExpect(jsonPath("$.cities[1].y").value(257.14))
            // neutral nation 0 excluded; only nation 1 present
            .andExpect(jsonPath("$.nations.length()").value(1))
            .andExpect(jsonPath("$.nations[0].id").value(1))
            .andExpect(jsonPath("$.nations[0].color").value("#c62828"))
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
}
