package opensamguk.gameapi.controller

import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * W9 (9a) slice test for [CityListController] — MockMvc standalone over mocked read repos.
 * public 도시일람 와이어 contract(`j_get_city_list`): 중립(0) 포함 nations + cityArgs 순서 cities 튜플 + 빈-world 200.
 */
class CityListControllerTest {

    private val cities = mock(CityReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(CityListController(cities, nations, generals)).build()

    @Test
    fun `city list carries neutral nation, gennum-power proxy, and city tuples`() {
        `when`(nations.findAll()).thenReturn(
            listOf(
                NationReadEntity(id = 1, name = "위", color = "#c62828", typeCode = "che_위나라", level = 5, capitalCityId = 3),
            ),
        )
        `when`(generals.countByNationId(1)).thenReturn(12L)
        `when`(generals.sumCrewByNationId(1)).thenReturn(34000L)
        `when`(cities.findAll()).thenReturn(
            listOf(
                CityReadEntity(id = 3, nationId = 1, name = "낙양", level = 8),
                CityReadEntity(id = 1, nationId = 0, name = "업", level = 6),
            ),
        )

        mockMvc().perform(get("/api/cities"))
            .andExpect(status().isOk)
            // cityArgsList fixed order
            .andExpect(jsonPath("$.cityArgsList[0]").value("city"))
            .andExpect(jsonPath("$.cityArgsList[1]").value("nation"))
            .andExpect(jsonPath("$.cityArgsList[2]").value("name"))
            .andExpect(jsonPath("$.cityArgsList[3]").value("level"))
            // nations: neutral(0) first, then nation 1 with proxy gennum/power
            .andExpect(jsonPath("$.nations.length()").value(2))
            .andExpect(jsonPath("$.nations[0].nation").value(0))
            .andExpect(jsonPath("$.nations[0].name").value("재야"))
            .andExpect(jsonPath("$.nations[0].color").value("#000000"))
            .andExpect(jsonPath("$.nations[0].gennum").value(1))
            .andExpect(jsonPath("$.nations[0].power").value(1))
            .andExpect(jsonPath("$.nations[1].nation").value(1))
            .andExpect(jsonPath("$.nations[1].type").value("che_위나라"))
            .andExpect(jsonPath("$.nations[1].capital").value(3))
            .andExpect(jsonPath("$.nations[1].gennum").value(12))
            .andExpect(jsonPath("$.nations[1].power").value(34000))
            // cities sorted by id: id1 then id3, tuple = [city, nation, name, level]
            .andExpect(jsonPath("$.cities.length()").value(2))
            .andExpect(jsonPath("$.cities[0][0]").value(1))
            .andExpect(jsonPath("$.cities[0][1]").value(0))
            .andExpect(jsonPath("$.cities[0][2]").value("업"))
            .andExpect(jsonPath("$.cities[0][3]").value(6))
            .andExpect(jsonPath("$.cities[1][0]").value(3))
            .andExpect(jsonPath("$.cities[1][2]").value("낙양"))
    }

    @Test
    fun `empty world returns 200 with only neutral nation and no cities`() {
        `when`(nations.findAll()).thenReturn(emptyList())
        `when`(cities.findAll()).thenReturn(emptyList())

        mockMvc().perform(get("/api/cities"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nations.length()").value(1)) // neutral only
            .andExpect(jsonPath("$.nations[0].nation").value(0))
            .andExpect(jsonPath("$.cities.length()").value(0))
    }
}
