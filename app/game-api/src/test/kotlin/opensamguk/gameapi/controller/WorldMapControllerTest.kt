package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional

/**
 * W9 (9a) slice test for [WorldMapController] — MockMvc standalone over mocked read repos (NO Spring
 * context, NO Testcontainers; [MapPreviewControllerTest] 패턴). 인게임 `getWorldMap` 와이어 contract
 * (compact tuple cityList/nationList) + fog 게이트 분기(중립 익명 / `?generalId=` fog) + 빈-world 200을 검증.
 */
class WorldMapControllerTest {

    private val owners = mock(GeneralOwnerRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    private val resolver = fixtureGeneralResolver(owners, generals, nations)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(WorldMapController(resolver, world, generals, nations, cities))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun seedWorld() {
        `when`(world.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "che_1010",
                    currentYear = 200,
                    currentMonth = 3,
                    // ScenarioImporter가 실제로 쓰는 PHP 정본 키 = 소문자 `startyear`(PR #31 케이싱 분열).
                    config = linkedMapOf("startyear" to 180, "mapName" to "han"),
                ),
            ),
        )
        `when`(nations.findAll()).thenReturn(
            listOf(
                NationReadEntity(id = 2, name = "촉", color = "#2e7d32", capitalCityId = 5),
                NationReadEntity(id = 1, name = "위", color = "#c62828", capitalCityId = 3),
            ),
        )
        `when`(cities.findAll()).thenReturn(
            listOf(
                CityReadEntity(id = 3, nationId = 1, level = 8, frontState = 1, state = 7, region = 2, supplyState = 0),
                CityReadEntity(id = 1, nationId = 2, level = 6, frontState = 0, region = 1, supplyState = 1),
            ),
        )
    }

    @Test
    fun `anonymous neutral map has compact tuples and empty fog`() {
        seedWorld()

        mockMvc().perform(get("/api/map"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.mapName").value("han"))
            .andExpect(jsonPath("$.startYear").value(180))
            .andExpect(jsonPath("$.year").value(200))
            .andExpect(jsonPath("$.month").value(3))
            // cityList sorted by id: id1 then id3, tuple = [city, level, state, nation, region, supply]
            .andExpect(jsonPath("$.cityList.length()").value(2))
            .andExpect(jsonPath("$.cityList[0][0]").value(1))
            .andExpect(jsonPath("$.cityList[0][1]").value(6))
            .andExpect(jsonPath("$.cityList[0][2]").value(0)) // state
            .andExpect(jsonPath("$.cityList[0][3]").value(2)) // nation
            .andExpect(jsonPath("$.cityList[0][4]").value(1)) // region
            .andExpect(jsonPath("$.cityList[0][5]").value(1)) // supply
            .andExpect(jsonPath("$.cityList[1][0]").value(3))
            .andExpect(jsonPath("$.cityList[1][2]").value(7)) // state (재해/사건 코드 — func_map.php tuple state자리, front_state 아님)
            // nationList sorted by id: nation1 then nation2, tuple = [nation, name, color, capital]
            .andExpect(jsonPath("$.nationList.length()").value(2))
            .andExpect(jsonPath("$.nationList[0][0]").value(1))
            .andExpect(jsonPath("$.nationList[0][1]").value("위"))
            .andExpect(jsonPath("$.nationList[0][2]").value("#c62828"))
            .andExpect(jsonPath("$.nationList[0][3]").value(3))
            // anonymous → empty fog
            .andExpect(jsonPath("$.spyList").isEmpty)
            .andExpect(jsonPath("$.shownByGeneralList").isEmpty)
            .andExpect(jsonPath("$.myCity").doesNotExist())
            .andExpect(jsonPath("$.myNation").doesNotExist())
    }

    @Test
    fun `showMe with generalId fallback reveals myCity myNation spy and shownBy`() {
        seedWorld()
        // ?generalId= transition fallback general: nation 1, city 3.
        `when`(generals.findById(7)).thenReturn(
            Optional.of(GeneralReadEntity(id = 7, nationId = 1, cityId = 3)),
        )
        // nation 1 carries a spy meta map {3:2}.
        `when`(nations.findById(1)).thenReturn(
            Optional.of(NationReadEntity(id = 1, name = "위", color = "#c62828", meta = linkedMapOf("spy" to linkedMapOf("3" to 2)))),
        )
        `when`(generals.findDistinctCityIdByNationId(1)).thenReturn(listOf(3))

        mockMvc().perform(get("/api/map?showMe=1&generalId=7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myCity").value(3))
            .andExpect(jsonPath("$.myNation").value(1))
            .andExpect(jsonPath("$.spyList.3").value(2))
            .andExpect(jsonPath("$.shownByGeneralList[0]").value(3))
    }

    @Test
    fun `neutralView hides myNation even with a general`() {
        seedWorld()
        `when`(generals.findById(7)).thenReturn(
            Optional.of(GeneralReadEntity(id = 7, nationId = 1, cityId = 3)),
        )

        mockMvc().perform(get("/api/map?neutralView=1&showMe=1&generalId=7"))
            .andExpect(status().isOk)
            // neutralView → myNation null → no spy / no shownBy even though general resolves
            .andExpect(jsonPath("$.myNation").doesNotExist())
            .andExpect(jsonPath("$.myCity").value(3))
            .andExpect(jsonPath("$.spyList").isEmpty)
            .andExpect(jsonPath("$.shownByGeneralList").isEmpty)
    }

    @Test
    fun `admin sees every city through fog`() {
        seedWorld()
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            1L,
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )

        try {
            mockMvc().perform(get("/api/map"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.spyList.1").value(1))
                .andExpect(jsonPath("$.spyList.3").value(1))
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `empty world returns 200 with empty lists and zero clock`() {
        `when`(world.findAll()).thenReturn(emptyList())
        `when`(nations.findAll()).thenReturn(emptyList())
        `when`(cities.findAll()).thenReturn(emptyList())

        mockMvc().perform(get("/api/map"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.year").value(0))
            .andExpect(jsonPath("$.month").value(0))
            .andExpect(jsonPath("$.startYear").value(0))
            .andExpect(jsonPath("$.cityList.length()").value(0))
            .andExpect(jsonPath("$.nationList.length()").value(0))
    }
}
