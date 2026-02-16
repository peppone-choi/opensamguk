package com.opensam.engine

import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import com.opensam.service.MapService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.OffsetDateTime

class EconomyServiceTest {

    private lateinit var service: EconomyService
    private lateinit var cityRepository: CityRepository
    private lateinit var nationRepository: NationRepository
    private lateinit var generalRepository: GeneralRepository
    private lateinit var mapService: MapService

    @BeforeEach
    fun setUp() {
        cityRepository = mock(CityRepository::class.java)
        nationRepository = mock(NationRepository::class.java)
        generalRepository = mock(GeneralRepository::class.java)
        mapService = mock(MapService::class.java)
        service = EconomyService(cityRepository, nationRepository, generalRepository, mapService)
    }

    private fun createWorld(year: Short = 200, month: Short = 3): WorldState {
        return WorldState(
            id = 1,
            scenarioCode = "test",
            currentYear = year,
            currentMonth = month,
            tickSeconds = 300,
        )
    }

    private fun createCity(
        id: Long = 1,
        nationId: Long = 1,
        pop: Int = 10000,
        popMax: Int = 50000,
        agri: Int = 500,
        agriMax: Int = 1000,
        comm: Int = 500,
        commMax: Int = 1000,
        secu: Int = 500,
        secuMax: Int = 1000,
        def: Int = 500,
        defMax: Int = 1000,
        wall: Int = 500,
        wallMax: Int = 1000,
        trust: Int = 80,
        supplyState: Short = 1,
        level: Short = 5,
        dead: Short = 0,
    ): City {
        return City(
            id = id,
            worldId = 1,
            name = "테스트도시$id",
            nationId = nationId,
            pop = pop,
            popMax = popMax,
            agri = agri,
            agriMax = agriMax,
            comm = comm,
            commMax = commMax,
            secu = secu,
            secuMax = secuMax,
            def = def,
            defMax = defMax,
            wall = wall,
            wallMax = wallMax,
            trust = trust,
            supplyState = supplyState,
            level = level,
            dead = dead,
        )
    }

    private fun createNation(
        id: Long = 1,
        gold: Int = 10000,
        rice: Int = 10000,
        level: Short = 1,
        rateTmp: Short = 15,
        bill: Short = 100,
        capitalCityId: Long? = 1,
    ): Nation {
        return Nation(
            id = id,
            worldId = 1,
            name = "테스트국가",
            color = "#FF0000",
            gold = gold,
            rice = rice,
            level = level,
            rateTmp = rateTmp,
            bill = bill,
            capitalCityId = capitalCityId,
        )
    }

    private fun createGeneral(
        id: Long = 1,
        nationId: Long = 1,
        cityId: Long = 1,
        gold: Int = 1000,
        rice: Int = 1000,
        dedication: Int = 1000,
        officerLevel: Short = 1,
        officerCity: Int = 0,
        npcState: Short = 0,
    ): General {
        return General(
            id = id,
            worldId = 1,
            name = "테스트장수$id",
            nationId = nationId,
            cityId = cityId,
            gold = gold,
            rice = rice,
            dedication = dedication,
            officerLevel = officerLevel,
            officerCity = officerCity,
            npcState = npcState,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun setupRepos(world: WorldState, cities: List<City>, nations: List<Nation>, generals: List<General>) {
        `when`(cityRepository.findByWorldId(world.id.toLong())).thenReturn(cities)
        `when`(nationRepository.findByWorldId(world.id.toLong())).thenReturn(nations)
        `when`(generalRepository.findByWorldId(world.id.toLong())).thenReturn(generals)
    }

    // ========== processMonthly: income adds gold/rice to nation ==========

    @Test
    fun `processMonthly adds income to nation treasury`() {
        val world = createWorld(month = 3)
        val nation = createNation(gold = 0, rice = 0, rateTmp = 15, bill = 0)
        val city = createCity(nationId = 1, pop = 10000, comm = 500, commMax = 1000, agri = 500, agriMax = 1000, trust = 80, supplyState = 1)
        val general = createGeneral(nationId = 1, dedication = 0)

        setupRepos(world, listOf(city), listOf(nation), listOf(general))
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        assertTrue(nation.gold > 0, "Nation should have gained gold income")
        assertTrue(nation.rice > 0, "Nation should have gained rice income")
    }

    @Test
    fun `processMonthly skips unsupplied city for income`() {
        val world = createWorld(month = 3)
        val nation = createNation(gold = 0, rice = 0, rateTmp = 15, bill = 0)
        val city = createCity(nationId = 1, supplyState = 0)
        val general = createGeneral(nationId = 1, dedication = 0)

        setupRepos(world, listOf(city), listOf(nation), listOf(general))
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        assertEquals(0, nation.gold, "Unsupplied city should produce no gold income")
    }

    // ========== processMonthly: salary distribution ==========

    @Test
    fun `processMonthly distributes salary to generals based on dedication`() {
        val world = createWorld(month = 3)
        val nation = createNation(gold = 50000, rice = 50000, rateTmp = 15, bill = 100)
        val city = createCity(nationId = 1, pop = 30000, comm = 800, commMax = 1000, trust = 100, supplyState = 1)
        val general = createGeneral(nationId = 1, gold = 0, rice = 0, dedication = 10000)

        setupRepos(world, listOf(city), listOf(nation), listOf(general))
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        assertTrue(general.gold > 0, "General should receive gold salary")
        assertTrue(general.rice > 0, "General should receive rice salary")
    }

    @Test
    fun `processMonthly npcState 5 generals excluded from salary`() {
        val world = createWorld(month = 3)
        val nation = createNation(gold = 50000, rice = 50000, rateTmp = 15, bill = 100)
        val city = createCity(nationId = 1, pop = 30000, comm = 800, commMax = 1000, trust = 100, supplyState = 1)
        val general = createGeneral(nationId = 1, gold = 0, rice = 0, dedication = 10000, npcState = 5)

        setupRepos(world, listOf(city), listOf(nation), listOf(general))
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        assertEquals(0, general.gold, "Dead general should not receive salary")
    }

    // ========== processMonthly: war income ==========

    @Test
    fun `processMonthly converts dead count to gold and population`() {
        val world = createWorld(month = 3)
        val nation = createNation(gold = 0, rice = 0, rateTmp = 15, bill = 0)
        val city = createCity(nationId = 1, dead = 100, pop = 5000, popMax = 50000, supplyState = 1)
        val general = createGeneral(nationId = 1, dedication = 0)

        setupRepos(world, listOf(city), listOf(nation), listOf(general))
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        // dead / 10 = 10 gold + regular income
        assertTrue(nation.gold >= 10, "Nation should gain gold from dead count")
        // dead * 0.2 = 20 pop gain
        assertTrue(city.pop >= 5020, "City should gain population from dead")
        assertEquals(0, city.dead.toInt(), "Dead should be reset to 0")
    }

    // ========== processMonthly: semi-annual (month 1, 7) ==========

    @Test
    fun `processMonthly runs semi-annual on January`() {
        val world = createWorld(month = 1)
        // rateTmp=25 (>20) => genericRatio = (20-25)/200 = -0.025, so infrastructure decays
        val nation = createNation(gold = 50000, rice = 50000, rateTmp = 25, bill = 0)
        val city = createCity(nationId = 1, agri = 1000, agriMax = 1000, supplyState = 1)
        val general = createGeneral(nationId = 1, gold = 20000, rice = 20000, dedication = 0)

        setupRepos(world, listOf(city), listOf(nation), listOf(general))
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        // 1% decay + negative growth rate => net decay
        assertTrue(city.agri < 1000, "City agri should decay in semi-annual with high tax")
    }

    @Test
    fun `processMonthly runs semi-annual on July`() {
        val world = createWorld(month = 7)
        val nation = createNation(gold = 50000, rice = 50000, rateTmp = 25, bill = 0)
        val city = createCity(nationId = 1, agri = 1000, agriMax = 1000, supplyState = 1)
        val general = createGeneral(nationId = 1, gold = 20000, rice = 20000, dedication = 0)

        setupRepos(world, listOf(city), listOf(nation), listOf(general))
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        assertTrue(city.agri < 1000, "City agri should decay in semi-annual July with high tax")
    }

    @Test
    fun `processMonthly does not run semi-annual on other months`() {
        val world = createWorld(month = 3)
        val nation = createNation(gold = 0, rice = 0, rateTmp = 15, bill = 0)
        // Neutral city should have trust reset to 50 only in semi-annual
        val city = createCity(id = 2, nationId = 0, trust = 80, supplyState = 1, agri = 1000, agriMax = 1000)

        setupRepos(world, listOf(city), listOf(nation), emptyList())
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        // Neutral city trust should NOT be reset to 50 in month 3
        assertEquals(80, city.trust, "Neutral city trust should not change outside semi-annual")
    }

    // ========== Semi-annual: neutral city double decay ==========

    @Test
    fun `semi-annual resets neutral city trust to 50 and double-decays`() {
        val world = createWorld(month = 1)
        val nation = createNation(gold = 0, rice = 0, rateTmp = 15, bill = 0)
        val neutralCity = createCity(id = 2, nationId = 0, trust = 80, agri = 1000, agriMax = 1000, supplyState = 1)

        setupRepos(world, listOf(neutralCity), listOf(nation), emptyList())
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        assertEquals(50, neutralCity.trust, "Neutral city trust should be reset to 50")
        // Double decay: 1000 * 0.99 * 0.99 = 980
        assertEquals(980, neutralCity.agri, "Neutral city should suffer double 1% decay")
    }

    // ========== Semi-annual: general resource decay ==========

    @Test
    fun `semi-annual decays general gold above 10000 by 3 percent`() {
        val world = createWorld(month = 1)
        val nation = createNation(gold = 0, rice = 0, rateTmp = 15, bill = 0)
        val general = createGeneral(nationId = 1, gold = 20000, rice = 500, dedication = 0)
        val city = createCity(nationId = 1, supplyState = 1)

        setupRepos(world, listOf(city), listOf(nation), listOf(general))
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        val goldBefore = general.gold
        service.processMonthly(world)

        // Gold > 10000 decays by 3%, but salary is also added, so just check relative decay
        // 20000 * 0.97 = 19400 (before salary)
        assertTrue(general.gold < goldBefore, "General gold above 10000 should decay")
    }

    @Test
    fun `semi-annual decays nation gold above 100000 by 5 percent`() {
        val world = createWorld(month = 1)
        val nation = createNation(gold = 200000, rice = 200000, rateTmp = 15, bill = 0)
        val city = createCity(nationId = 1, supplyState = 1)

        setupRepos(world, listOf(city), listOf(nation), emptyList())
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        // 200000 gets income first, then * 0.95
        // The exact value depends on income, but it should be less than 200000 + income * 0.95
        // Nation gold > 100000 → 5% decay applied
        assertTrue(nation.gold < 200000, "Nation gold above 100000 should decay by 5%")
    }

    // ========== updateNationLevel ==========

    @Test
    fun `nation levels up when gaining enough high-level cities`() {
        val world = createWorld(month = 3)
        val nation = createNation(gold = 0, rice = 0, level = 1, rateTmp = 15, bill = 0)
        // 5 cities with level >= 4 => nation level should be 3 (threshold: 5)
        val cities = (1..5).map { createCity(id = it.toLong(), nationId = 1, level = 5, supplyState = 1) }
        val general = createGeneral(nationId = 1, dedication = 0)

        setupRepos(world, cities, listOf(nation), listOf(general))
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        assertTrue(nation.level >= 3, "Nation should level up to at least 3 with 5 high-level cities")
    }

    @Test
    fun `nation does not level down`() {
        val world = createWorld(month = 3)
        val nation = createNation(gold = 0, rice = 0, level = 5, rateTmp = 15, bill = 0)
        // Only 1 high level city, threshold for level 1 = 1
        val city = createCity(nationId = 1, level = 5, supplyState = 1)
        val general = createGeneral(nationId = 1, dedication = 0)

        setupRepos(world, listOf(city), listOf(nation), listOf(general))
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        // updateNationLevel only upgrades (newLevel > nation.level check)
        assertEquals(5.toShort(), nation.level, "Nation level should not decrease")
    }

    // ========== Capital city income bonus ==========

    @Test
    fun `capital city produces more income than non-capital`() {
        val world = createWorld(month = 3)
        // Test with two nations: one with capital matching city, one without
        val nation1 = createNation(id = 1, gold = 0, rice = 0, rateTmp = 15, bill = 0, capitalCityId = 1)
        val nation2 = createNation(id = 2, gold = 0, rice = 0, rateTmp = 15, bill = 0, capitalCityId = 99)
        val city1 = createCity(id = 1, nationId = 1, pop = 10000, comm = 500, commMax = 1000, trust = 80, supplyState = 1)
        val city2 = createCity(id = 2, nationId = 2, pop = 10000, comm = 500, commMax = 1000, trust = 80, supplyState = 1)
        val g1 = createGeneral(id = 1, nationId = 1, dedication = 0)
        val g2 = createGeneral(id = 2, nationId = 2, dedication = 0)

        setupRepos(world, listOf(city1, city2), listOf(nation1, nation2), listOf(g1, g2))
        `when`(mapService.getAdjacentCities(anyString(), anyInt())).thenReturn(emptyList())

        service.processMonthly(world)

        assertTrue(nation1.gold > nation2.gold, "Capital city should produce more gold income")
    }
}
