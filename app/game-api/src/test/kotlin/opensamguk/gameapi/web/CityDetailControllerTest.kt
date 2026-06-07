package opensamguk.gameapi.web

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import org.hamcrest.Matchers.nullValue
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
 * 첩보(fog) 패러티: 아국/첩보/주둔이면 full detail(visible=true), 아니면 마스킹(visible=false, 수치 null).
 * 등급/지역 한글명(levelName/regionName)과 404 absent-city 계약도 검증.
 */
class CityDetailControllerTest {

    private val resolver = mock(GeneralResolver::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(CityDetailController(resolver, cities, generals, nations)).build()

    private fun heoChang() = CityReadEntity(
        id = 5, name = "허창", level = 5, nationId = 1, region = 3,
        population = 50000, populationMax = 100000,
        agriculture = 4000, agricultureMax = 8000,
        commerce = 3000, commerceMax = 8000,
        security = 1000, securityMax = 2000,
        defense = 500, defenseMax = 1000,
        wall = 800, wallMax = 1500,
        trust = 82.0, trade = 100, supplyState = 1, frontState = 0,
    )

    @Test
    fun `아국 도시는 full detail + 등급·지역 한글명`() {
        `when`(cities.findById(5)).thenReturn(Optional.of(heoChang()))
        `when`(generals.countByCityId(5)).thenReturn(7L)
        // ?generalId=7 → nation 1 소속(도시 소유국) → visible.
        `when`(generals.findById(7)).thenReturn(Optional.of(GeneralReadEntity(id = 7, nationId = 1, cityId = 5)))

        mockMvc().perform(get("/api/city/{id}", 5).param("generalId", "7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(5))
            .andExpect(jsonPath("$.name").value("허창"))
            .andExpect(jsonPath("$.level").value(5))
            .andExpect(jsonPath("$.levelName").value("소"))   // getCityLevelList()[5]
            .andExpect(jsonPath("$.region").value(3))
            .andExpect(jsonPath("$.regionName").value("서북")) // CityConst.regionMap[3]
            .andExpect(jsonPath("$.nationId").value(1))
            .andExpect(jsonPath("$.visible").value(true))
            .andExpect(jsonPath("$.population").value(50000))
            .andExpect(jsonPath("$.populationMax").value(100000))
            .andExpect(jsonPath("$.agriculture").value(4000))
            .andExpect(jsonPath("$.wall").value(800))
            .andExpect(jsonPath("$.trust").value(82.0))
            .andExpect(jsonPath("$.trade").value(100))
            .andExpect(jsonPath("$.officers").value(7))
    }

    @Test
    fun `타국·익명은 첩보 없으면 내정 마스킹(visible false, 수치 null)`() {
        `when`(cities.findById(5)).thenReturn(Optional.of(heoChang()))

        // 익명(identity 없음) → myNation null → 비가시. 표시 표면은 보이되 내정/방어 수치는 null.
        mockMvc().perform(get("/api/city/{id}", 5))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(5))
            .andExpect(jsonPath("$.name").value("허창"))
            .andExpect(jsonPath("$.levelName").value("소"))
            .andExpect(jsonPath("$.regionName").value("서북"))
            .andExpect(jsonPath("$.visible").value(false))
            .andExpect(jsonPath("$.population").value(nullValue()))
            .andExpect(jsonPath("$.trust").value(nullValue()))
            .andExpect(jsonPath("$.officers").value(nullValue()))
            .andExpect(jsonPath("$.supplyState").value(1)) // 맵 타일 노출값 — 마스킹 제외
    }

    @Test
    fun `404 when the city id is absent`() {
        `when`(cities.findById(999)).thenReturn(Optional.empty())

        mockMvc().perform(get("/api/city/{id}", 999))
            .andExpect(status().isNotFound)
    }
}
