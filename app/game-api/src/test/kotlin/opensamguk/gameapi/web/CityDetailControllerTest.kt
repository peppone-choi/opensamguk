package opensamguk.gameapi.web

import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional

/**
 * F2 Wave 6 slice test for [CityDetailController] — MockMvc standalone over mocked read repos.
 * Asserts the full city read shape (value+max pairs, trust/trade/region, officer count) and the
 * 404 absent-city contract.
 */
class CityDetailControllerTest {

    private val cities = mock(CityReadRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(CityDetailController(cities, generals)).build()

    @Test
    fun `returns the full city detail with the stationed officer count`() {
        `when`(cities.findById(5)).thenReturn(
            Optional.of(
                CityReadEntity(
                    id = 5, name = "허창", level = 5, nationId = 1, region = 3,
                    population = 50000, populationMax = 100000,
                    agriculture = 4000, agricultureMax = 8000,
                    commerce = 3000, commerceMax = 8000,
                    security = 1000, securityMax = 2000,
                    defense = 500, defenseMax = 1000,
                    wall = 800, wallMax = 1500,
                    trust = 82.0, trade = 100, supplyState = 1, frontState = 0,
                ),
            ),
        )
        `when`(generals.countByCityId(5)).thenReturn(7L)

        mockMvc().perform(get("/api/city/{id}", 5))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(5))
            .andExpect(jsonPath("$.name").value("허창"))
            .andExpect(jsonPath("$.level").value(5))
            .andExpect(jsonPath("$.nationId").value(1))
            .andExpect(jsonPath("$.region").value(3))
            .andExpect(jsonPath("$.population").value(50000))
            .andExpect(jsonPath("$.populationMax").value(100000))
            .andExpect(jsonPath("$.agriculture").value(4000))
            .andExpect(jsonPath("$.agricultureMax").value(8000))
            .andExpect(jsonPath("$.wall").value(800))
            .andExpect(jsonPath("$.wallMax").value(1500))
            .andExpect(jsonPath("$.trust").value(82.0))
            .andExpect(jsonPath("$.trade").value(100))
            .andExpect(jsonPath("$.officers").value(7))
    }

    @Test
    fun `404 when the city id is absent`() {
        `when`(cities.findById(999)).thenReturn(Optional.empty())

        mockMvc().perform(get("/api/city/{id}", 999))
            .andExpect(status().isNotFound)
    }
}
