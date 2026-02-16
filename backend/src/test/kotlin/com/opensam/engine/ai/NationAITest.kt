package com.opensam.engine.ai

import com.opensam.entity.City
import com.opensam.entity.Diplomacy
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.repository.CityRepository
import com.opensam.repository.DiplomacyRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import kotlin.random.Random

class NationAITest {

    private lateinit var ai: NationAI
    private lateinit var cityRepository: CityRepository
    private lateinit var generalRepository: GeneralRepository
    private lateinit var nationRepository: NationRepository
    private lateinit var diplomacyRepository: DiplomacyRepository

    @BeforeEach
    fun setUp() {
        cityRepository = mock(CityRepository::class.java)
        generalRepository = mock(GeneralRepository::class.java)
        nationRepository = mock(NationRepository::class.java)
        diplomacyRepository = mock(DiplomacyRepository::class.java)
        ai = NationAI(cityRepository, generalRepository, nationRepository, diplomacyRepository)
    }

    private fun createWorld(year: Short = 200, month: Short = 3): WorldState {
        return WorldState(id = 1, scenarioCode = "test", currentYear = year, currentMonth = month, tickSeconds = 300)
    }

    private fun createNation(
        id: Long = 1,
        gold: Int = 10000,
        rice: Int = 10000,
        power: Int = 100,
        warState: Short = 0,
        strategicCmdLimit: Short = 0,
    ): Nation {
        return Nation(
            id = id,
            worldId = 1,
            name = "국가$id",
            color = "#FF0000",
            gold = gold,
            rice = rice,
            power = power,
            warState = warState,
            strategicCmdLimit = strategicCmdLimit,
            capitalCityId = 1,
        )
    }

    private fun createGeneral(
        id: Long = 1,
        nationId: Long = 1,
        cityId: Long = 1,
        npcState: Short = 2,
        officerLevel: Short = 1,
        dedication: Int = 100,
    ): General {
        return General(
            id = id,
            worldId = 1,
            name = "장수$id",
            nationId = nationId,
            cityId = cityId,
            npcState = npcState,
            officerLevel = officerLevel,
            dedication = dedication,
            leadership = 50,
            strength = 50,
            intel = 50,
            politics = 50,
            charm = 50,
            gold = 1000,
            rice = 1000,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createCity(
        id: Long = 1,
        nationId: Long = 1,
        level: Short = 5,
    ): City {
        return City(
            id = id,
            worldId = 1,
            name = "도시$id",
            nationId = nationId,
            level = level,
            pop = 10000,
            popMax = 50000,
            agri = 500,
            agriMax = 1000,
            comm = 500,
            commMax = 1000,
            secu = 500,
            secuMax = 1000,
        )
    }

    private fun setupRepos(
        nation: Nation,
        cities: List<City>,
        generals: List<General>,
        allNations: List<Nation> = listOf(nation),
        diplomacies: List<Diplomacy> = emptyList(),
    ) {
        `when`(cityRepository.findByNationId(nation.id)).thenReturn(cities)
        `when`(generalRepository.findByNationId(nation.id)).thenReturn(generals)
        `when`(nationRepository.findByWorldId(1L)).thenReturn(allNations)
        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(1L)).thenReturn(diplomacies)
    }

    @Test
    fun `should declare war when stronger and sufficiently prepared`() {
        val nation = createNation(gold = 10000, rice = 10000, power = 200)
        val target = createNation(id = 2, power = 50)
        `when`(cityRepository.findByNationId(1)).thenReturn(listOf(createCity(id = 1), createCity(id = 2)))
        `when`(cityRepository.findByNationId(2)).thenReturn(listOf(createCity(id = 3, nationId = 2)))
        `when`(generalRepository.findByNationId(1)).thenReturn(listOf(createGeneral(id = 1), createGeneral(id = 2)))
        `when`(generalRepository.findByNationId(2)).thenReturn(listOf(createGeneral(id = 3, nationId = 2)))

        assertTrue(ai.shouldDeclareWar(nation, target, createWorld()))
    }

    @Test
    fun `should not declare war when weaker than target`() {
        val nation = createNation(gold = 10000, rice = 10000, power = 50)
        val target = createNation(id = 2, power = 200)
        `when`(cityRepository.findByNationId(1)).thenReturn(listOf(createCity()))
        `when`(cityRepository.findByNationId(2)).thenReturn(listOf(createCity(id = 2, nationId = 2)))
        `when`(generalRepository.findByNationId(1)).thenReturn(listOf(createGeneral()))
        `when`(generalRepository.findByNationId(2)).thenReturn(listOf(createGeneral(id = 2, nationId = 2)))

        assertFalse(ai.shouldDeclareWar(nation, target, createWorld()))
    }

    @Test
    fun `decideNationAction returns Nation휴식 when nation gold is very low`() {
        val nation = createNation(gold = 500)
        setupRepos(nation, listOf(createCity()), listOf(createGeneral()))

        val action = ai.decideNationAction(nation, createWorld(), Random(42))
        assertEquals("Nation휴식", action)
    }

    @Test
    fun `decideNationAction returns war strategic action when at war and strategic commands available`() {
        val nation = createNation(gold = 10000, rice = 10000, warState = 1, strategicCmdLimit = 2)
        setupRepos(nation, listOf(createCity()), listOf(createGeneral()))

        val action = ai.decideNationAction(nation, createWorld(), Random(42))
        assertTrue(action in listOf("급습", "의병모집", "필사즉생"))
    }

    @Test
    fun `decideNationAction returns Nation휴식 when at war but no strategic command available`() {
        val nation = createNation(gold = 10000, rice = 10000, warState = 1, strategicCmdLimit = 0)
        setupRepos(nation, listOf(createCity()), listOf(createGeneral()))

        val action = ai.decideNationAction(nation, createWorld(), Random(42))
        assertEquals("Nation휴식", action)
    }

    @Test
    fun `decideNationAction returns 발령 when unassigned generals exist`() {
        val nation = createNation(gold = 10000, rice = 10000)
        val unassigned = createGeneral(id = 1, officerLevel = 0, npcState = 2)
        setupRepos(nation, listOf(createCity()), listOf(unassigned))

        val action = ai.decideNationAction(nation, createWorld(), Random(42))
        assertEquals("발령", action)
    }

    @Test
    fun `decideNationAction returns 증축 when nation can expand low-level city`() {
        val nation = createNation(gold = 6000, rice = 10000)
        val cities = listOf(createCity(id = 1, level = 4), createCity(id = 2, level = 5))
        val generals = listOf(createGeneral(id = 1, officerLevel = 1, dedication = 120))
        setupRepos(nation, cities, generals)

        val action = ai.decideNationAction(nation, createWorld(), Random(42))
        assertEquals("증축", action)
    }

    @Test
    fun `decideNationAction returns 포상 when generals have low dedication and nation has enough gold`() {
        val nation = createNation(gold = 4000, rice = 10000)
        val cities = listOf(createCity(level = 5))
        val lowDedGeneral = createGeneral(id = 1, officerLevel = 1, dedication = 50)
        setupRepos(nation, cities, listOf(lowDedGeneral))

        val action = ai.decideNationAction(nation, createWorld(), Random(42))
        assertEquals("포상", action)
    }

    @Test
    fun `decideNationAction falls back to Nation휴식 when no trigger condition matches`() {
        val nation = createNation(gold = 20000, rice = 20000, power = 100)
        val cities = listOf(createCity(level = 5))
        val generals = listOf(createGeneral(id = 1, officerLevel = 3, dedication = 120))
        setupRepos(
            nation = nation,
            cities = cities,
            generals = generals,
            allNations = listOf(nation),
            diplomacies = emptyList(),
        )

        val action = ai.decideNationAction(nation, createWorld(), Random(42))
        assertEquals("Nation휴식", action)
    }
}
