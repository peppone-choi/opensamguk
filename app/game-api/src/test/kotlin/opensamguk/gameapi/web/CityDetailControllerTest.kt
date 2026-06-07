package opensamguk.gameapi.web

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
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
    private val world = mock(WorldStateReadRepository::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(CityDetailController(resolver, cities, generals, nations, world)).build()

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

    @Test
    fun `아국 도시 상세 — 장수표·관직자·군사집계·셀렉터·갱신시각`() {
        `when`(cities.findById(5)).thenReturn(Optional.of(heoChang()))
        `when`(generals.countByCityId(5)).thenReturn(2L)
        // 조회자 = 태수(officer_level 4) nation 1 → 아국·관직자(셀렉터 아국도시 분기).
        val viewer = GeneralReadEntity(id = 100, nationId = 1, cityId = 5, officerLevel = 4)
        `when`(generals.findById(100)).thenReturn(Optional.of(viewer))

        // 국가 정적정보: nation 1(level 1, 이름), nation 2(적국).
        `when`(nations.findAll()).thenReturn(
            listOf(
                NationReadEntity(id = 1, name = "위", level = 1, color = "#ff0000"),
                NationReadEntity(id = 2, name = "촉", level = 1, color = "#00ff00"),
            ),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", level = 1)))
        `when`(nations.findById(2)).thenReturn(Optional.of(NationReadEntity(id = 2, name = "촉", level = 1)))

        // 관직자(officer_city=5) — 태수(lv4) + 종사(lv2). 군사(lv3)는 없음("-").
        `when`(generals.findByOfficerCityAndOfficerLevelInOrderByIdAsc(5, listOf(4, 3, 2))).thenReturn(
            listOf(
                GeneralReadEntity(id = 100, name = "조조", nationId = 1, officerLevel = 4, npcState = 0),
                GeneralReadEntity(id = 101, name = "순욱", nationId = 1, officerLevel = 2, npcState = 0),
            ),
        )

        // 도시 소재 장수: 아군(crew 1000, train/atmos 95→90·60·수비 집계) + 적군(nation 2, crew 500).
        `when`(generals.findByCityIdOrderByTurnTimeAsc(5)).thenReturn(
            listOf(
                GeneralReadEntity(
                    id = 100, name = "조조", nationId = 1, cityId = 5, officerLevel = 4, npcState = 0,
                    leadership = 90, strength = 80, intel = 95, crew = 1000, crewTypeId = 0,
                    train = 95, atmos = 95, picture = "cao.png",
                ),
                GeneralReadEntity(
                    id = 200, name = "관우", nationId = 2, cityId = 5, officerLevel = 4, npcState = 0,
                    leadership = 95, strength = 99, intel = 75, crew = 500, train = 90, atmos = 90,
                ),
            ),
        )

        // 셀렉터: 아국(nation 1) 도시 = 허창(5) 하나.
        `when`(cities.findAll()).thenReturn(listOf(heoChang()))
        `when`(generals.findDistinctCityIdByNationId(1)).thenReturn(listOf(5))

        // 갱신시각: world_state config["turntime"] → substr(5,14) = "06-07 12:34:56".
        `when`(world.findAll()).thenReturn(
            listOf(WorldStateReadEntity(id = 1, config = mapOf("turntime" to "2026-06-07 12:34:56"))),
        )

        mockMvc().perform(get("/api/city/{id}", 5).param("generalId", "100"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.visible").value(true))
            .andExpect(jsonPath("$.showDetailedInfo").value(true))
            .andExpect(jsonPath("$.cityName").value("허창"))
            .andExpect(jsonPath("$.lastExecute").value("06-07 12:34:56"))
            // 관직자행
            .andExpect(jsonPath("$.officerGovernor.name").value("조조"))
            .andExpect(jsonPath("$.officerStrategist.name").value("-")) // lv3 부재
            .andExpect(jsonPath("$.officerSecretary.name").value("순욱"))
            // 장수 상세표(2행) — 아군 조조 ourGeneral=true, 적군 관우 false(병종/훈련 마스킹).
            .andExpect(jsonPath("$.generals.length()").value(2))
            .andExpect(jsonPath("$.generals[0].name").value("조조"))
            .andExpect(jsonPath("$.generals[0].ourGeneral").value(true))
            .andExpect(jsonPath("$.generals[0].officerLevelText").value("태수")) // lv4 공통명칭
            .andExpect(jsonPath("$.generals[1].name").value("관우"))
            .andExpect(jsonPath("$.generals[1].ourGeneral").value(false))
            .andExpect(jsonPath("$.generals[1].train").value(-1)) // 비아국 마스킹
            .andExpect(jsonPath("$.generals[1].nationName").value("촉"))
            // 장수명 CSV 원천
            .andExpect(jsonPath("$.generalNames.length()").value(2))
            .andExpect(jsonPath("$.generalNames[0].name").value("조조"))
            // 군사 집계행 — 아군 1명 1000병(90/60/수비 모두 집계), 적군 1명 500병.
            .andExpect(jsonPath("$.military.crewTotal").value(1000))
            .andExpect(jsonPath("$.military.armedGenTotal").value(1))
            .andExpect(jsonPath("$.military.genTotal").value(1))
            .andExpect(jsonPath("$.military.crew90").value(1000))
            .andExpect(jsonPath("$.military.crew60").value(1000))
            .andExpect(jsonPath("$.military.enemyCrew").value(500))
            .andExpect(jsonPath("$.military.enemyArmedCnt").value(1))
            .andExpect(jsonPath("$.military.enemyCnt").value(1))
            // 도시선택 셀렉터 — 허창(본국, 선택됨).
            .andExpect(jsonPath("$.citySelector.length()").value(1))
            .andExpect(jsonPath("$.citySelector[0].cityId").value(5))
            .andExpect(jsonPath("$.citySelector[0].relation").value(1)) // 본국
            .andExpect(jsonPath("$.citySelector[0].selected").value(true))
    }

    @Test
    fun `비가시 도시는 장수표·군사집계가 비고 showDetailedInfo false`() {
        `when`(cities.findById(5)).thenReturn(Optional.of(heoChang()))
        // 익명 → 비가시.
        mockMvc().perform(get("/api/city/{id}", 5))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.showDetailedInfo").value(false))
            .andExpect(jsonPath("$.generals.length()").value(0))
            .andExpect(jsonPath("$.generalNames.length()").value(0))
            .andExpect(jsonPath("$.officerGovernor.name").value("-"))
            .andExpect(jsonPath("$.military.crewTotal").value(0))
            .andExpect(jsonPath("$.lastExecute").value(nullValue())) // world 미스텁 → null
    }
}
