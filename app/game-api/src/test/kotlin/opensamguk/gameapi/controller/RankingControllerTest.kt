package opensamguk.gameapi.controller

import opensamguk.gameapi.rank.RankReadService
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
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
 * F3 slice test for [RankingController] — MockMvc standalone over a real [RankReadService] backed by
 * mocked read repos (NO Spring context, NO Testcontainers). Asserts the exact JSON contract each
 * `web/game/app/game/rankings/<board>/page.tsx` interface consumes: the computed sort order/tiebreak
 * (best-generals/generals/kingdoms/npcs), the 재야 / #000000 neutral join, and the empty/zero defaults
 * (hall-of-fame/traffic/emperor) + the emperor/{id} 404.
 */
class RankingControllerTest {

    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(RankingController(RankReadService(generals, nations, cities))).build()

    private fun gen(
        id: Int,
        name: String,
        nationId: Int = 0,
        cityId: Int = 0,
        leadership: Int = 0,
        strength: Int = 0,
        intel: Int = 0,
        experience: Int = 0,
        dedication: Int = 0,
        officerLevel: Int = 0,
        crew: Int = 0,
        npcState: Int = 0,
    ) = GeneralReadEntity(
        id = id, name = name, nationId = nationId, cityId = cityId,
        leadership = leadership, strength = strength, intel = intel,
        experience = experience, dedication = dedication, officerLevel = officerLevel,
        crew = crew, npcState = npcState,
    )

    private fun nation(id: Int, name: String, color: String, level: Int = 0, gold: Int = 0, rice: Int = 0, capitalCityId: Int? = null) =
        NationReadEntity(id = id, name = name, color = color, level = level, gold = gold, rice = rice, capitalCityId = capitalCityId)

    private fun city(id: Int, name: String, nationId: Int = 0, level: Int = 0) =
        CityReadEntity(id = id, name = name, nationId = nationId, level = level)

    // ── 2.1 best-generals ──────────────────────────────────────────────────────────────────────────
    @Test
    fun `best-generals sorts by total desc, ties by id asc, with neutral join`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "위", "#c62828")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                gen(id = 5, name = "관우", nationId = 1, leadership = 90, strength = 95, intel = 70), // total 255
                gen(id = 2, name = "방랑", nationId = 0, leadership = 80, strength = 80, intel = 95),  // total 255, tie → id asc
                gen(id = 9, name = "장비", nationId = 1, leadership = 80, strength = 90, intel = 30),  // total 200
            ),
        )

        mockMvc().perform(get("/api/rankings/best-generals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            // tie at 255: id 2 before id 5
            .andExpect(jsonPath("$[0].rank").value(1))
            .andExpect(jsonPath("$[0].generalId").value(2))
            .andExpect(jsonPath("$[0].name").value("방랑"))
            .andExpect(jsonPath("$[0].nation").value("재야"))
            .andExpect(jsonPath("$[0].nationColor").value("#000000"))
            .andExpect(jsonPath("$[0].total").value(255))
            .andExpect(jsonPath("$[1].rank").value(2))
            .andExpect(jsonPath("$[1].generalId").value(5))
            .andExpect(jsonPath("$[1].nation").value("위"))
            .andExpect(jsonPath("$[1].nationColor").value("#c62828"))
            .andExpect(jsonPath("$[1].total").value(255))
            .andExpect(jsonPath("$[2].rank").value(3))
            .andExpect(jsonPath("$[2].generalId").value(9))
            .andExpect(jsonPath("$[2].total").value(200))
    }

    // ── 2.2 generals ───────────────────────────────────────────────────────────────────────────────
    @Test
    fun `generals default-sorts by experience desc and exposes devotion=dedication`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "촉", "#2e7d32")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                gen(id = 1, name = "유비", nationId = 1, officerLevel = 12, experience = 500, dedication = 80, crew = 1000),
                gen(id = 2, name = "제갈량", nationId = 1, officerLevel = 5, experience = 900, dedication = 50, crew = 0),
            ),
        )

        mockMvc().perform(get("/api/rankings/generals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].rank").value(1))
            .andExpect(jsonPath("$[0].generalId").value(2)) // exp 900 first
            .andExpect(jsonPath("$[0].experience").value(900))
            .andExpect(jsonPath("$[0].officerLevel").value(5))
            .andExpect(jsonPath("$[0].devotion").value(50))
            .andExpect(jsonPath("$[1].rank").value(2))
            .andExpect(jsonPath("$[1].generalId").value(1))
            .andExpect(jsonPath("$[1].devotion").value(80))
            .andExpect(jsonPath("$[1].crew").value(1000))
    }

    // ── 2.3 kingdoms ───────────────────────────────────────────────────────────────────────────────
    @Test
    fun `kingdoms sorts by power proxy desc, excludes nation 0, fills pop+capital`() {
        `when`(nations.findAll()).thenReturn(
            listOf(
                nation(0, "재야", "#000000"),
                nation(1, "위", "#c62828", level = 7, gold = 5000, rice = 3000, capitalCityId = 10),
                nation(2, "오", "#1565c0", level = 5, gold = 1000, rice = 2000, capitalCityId = 20),
            ),
        )
        `when`(cities.findAll()).thenReturn(
            listOf(city(10, "허창", nationId = 1), city(20, "건업", nationId = 2)),
        )
        `when`(generals.sumCrewByNationId(1)).thenReturn(8000L)
        `when`(generals.sumCrewByNationId(2)).thenReturn(12000L)
        `when`(cities.sumPopulationByNationId(1)).thenReturn(500000L)
        `when`(cities.sumPopulationByNationId(2)).thenReturn(300000L)
        `when`(generals.countByNationId(1)).thenReturn(10L)
        `when`(generals.countByNationId(2)).thenReturn(8L)
        `when`(cities.countByNationId(1)).thenReturn(3L)
        `when`(cities.countByNationId(2)).thenReturn(2L)

        mockMvc().perform(get("/api/rankings/kingdoms"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2)) // nation 0 excluded
            // 오 (power 12000) before 위 (power 8000)
            .andExpect(jsonPath("$[0].rank").value(1))
            .andExpect(jsonPath("$[0].nationId").value(2))
            .andExpect(jsonPath("$[0].name").value("오"))
            .andExpect(jsonPath("$[0].color").value("#1565c0"))
            .andExpect(jsonPath("$[0].power").value(12000))
            .andExpect(jsonPath("$[0].pop").value(300000))
            .andExpect(jsonPath("$[0].genNum").value(8))
            .andExpect(jsonPath("$[0].cityCount").value(2))
            .andExpect(jsonPath("$[0].capitalName").value("건업"))
            .andExpect(jsonPath("$[1].rank").value(2))
            .andExpect(jsonPath("$[1].nationId").value(1))
            .andExpect(jsonPath("$[1].level").value(7))
            .andExpect(jsonPath("$[1].gold").value(5000))
            .andExpect(jsonPath("$[1].pop").value(500000))
            .andExpect(jsonPath("$[1].capitalName").value("허창"))
    }

    // ── 2.4 npcs ───────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `npcs lists npc_state=1 by total desc with city name and no rank field`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "위", "#c62828")))
        `when`(cities.findAll()).thenReturn(listOf(city(7, "낙양", nationId = 1)))
        `when`(generals.findByNpcStateOrderByIdAsc(1)).thenReturn(
            listOf(
                gen(id = 3, name = "악령갑", nationId = 1, cityId = 7, leadership = 50, strength = 50, intel = 50, npcState = 1), // 150
                gen(id = 4, name = "악령을", nationId = 0, cityId = 0, leadership = 70, strength = 70, intel = 70, npcState = 1), // 210
            ),
        )

        mockMvc().perform(get("/api/rankings/npcs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            // total desc: 악령을 (210) first
            .andExpect(jsonPath("$[0].generalId").value(4))
            .andExpect(jsonPath("$[0].name").value("악령을"))
            .andExpect(jsonPath("$[0].nation").value("재야"))
            .andExpect(jsonPath("$[0].cityName").value("")) // cityId 0 → ""
            .andExpect(jsonPath("$[0].devotion").value(0))
            .andExpect(jsonPath("$[1].generalId").value(3))
            .andExpect(jsonPath("$[1].cityName").value("낙양"))
            // no rank field in the NpcGeneral contract
            .andExpect(jsonPath("$[0].rank").doesNotExist())
    }

    // ── 2.5 hall-of-fame (empty default) ─────────────────────────────────────────────────────────────
    @Test
    fun `hall-of-fame returns empty array (F3 default, no fabrication)`() {
        mockMvc().perform(get("/api/rankings/hall-of-fame"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    // ── 2.6 traffic (zero-fill) ──────────────────────────────────────────────────────────────────────
    @Test
    fun `traffic returns zeroed summary with empty history`() {
        mockMvc().perform(get("/api/rankings/traffic"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.todayUnique").value(0))
            .andExpect(jsonPath("$.todayViews").value(0))
            .andExpect(jsonPath("$.weekUnique").value(0))
            .andExpect(jsonPath("$.monthViews").value(0))
            .andExpect(jsonPath("$.peakConcurrent").value(0))
            .andExpect(jsonPath("$.currentOnline").value(0))
            .andExpect(jsonPath("$.history.length()").value(0))
    }

    // ── 2.7 emperor (empty default) ──────────────────────────────────────────────────────────────────
    @Test
    fun `emperor returns empty array (F3 default, no unification table)`() {
        mockMvc().perform(get("/api/rankings/emperor"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    // ── 2.8 emperor detail (404) ─────────────────────────────────────────────────────────────────────
    @Test
    fun `emperor detail returns 404 (no emperior table)`() {
        mockMvc().perform(get("/api/rankings/emperor/1"))
            .andExpect(status().isNotFound)
    }
}
