package com.opensam.engine.ai

import com.opensam.entity.*
import com.opensam.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.OffsetDateTime
import java.util.*
import kotlin.random.Random

class GeneralAITest {

    private lateinit var ai: GeneralAI
    private lateinit var generalRepository: GeneralRepository
    private lateinit var cityRepository: CityRepository
    private lateinit var nationRepository: NationRepository
    private lateinit var diplomacyRepository: DiplomacyRepository

    @BeforeEach
    fun setUp() {
        generalRepository = mock(GeneralRepository::class.java)
        cityRepository = mock(CityRepository::class.java)
        nationRepository = mock(NationRepository::class.java)
        diplomacyRepository = mock(DiplomacyRepository::class.java)
        ai = GeneralAI(generalRepository, cityRepository, nationRepository, diplomacyRepository)
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

    private fun createGeneral(
        id: Long = 1,
        nationId: Long = 1,
        cityId: Long = 1,
        leadership: Short = 50,
        strength: Short = 50,
        intel: Short = 50,
        crew: Int = 0,
        train: Short = 50,
        atmos: Short = 50,
        gold: Int = 1000,
        rice: Int = 1000,
        officerLevel: Short = 1,
        npcState: Short = 2,
        injury: Short = 0,
        dedication: Int = 100,
    ): General {
        return General(
            id = id,
            worldId = 1,
            name = "NPC장수$id",
            nationId = nationId,
            cityId = cityId,
            leadership = leadership,
            strength = strength,
            intel = intel,
            crew = crew,
            train = train,
            atmos = atmos,
            gold = gold,
            rice = rice,
            officerLevel = officerLevel,
            npcState = npcState,
            injury = injury,
            dedication = dedication,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createCity(
        id: Long = 1,
        nationId: Long = 1,
        agri: Int = 500,
        agriMax: Int = 1000,
        comm: Int = 500,
        commMax: Int = 1000,
        secu: Int = 500,
        secuMax: Int = 1000,
        frontState: Short = 0,
    ): City {
        return City(
            id = id,
            worldId = 1,
            name = "도시$id",
            nationId = nationId,
            agri = agri,
            agriMax = agriMax,
            comm = comm,
            commMax = commMax,
            secu = secu,
            secuMax = secuMax,
            frontState = frontState,
            pop = 60000,
            popMax = 100000,
        )
    }

    private fun createNation(
        id: Long = 1,
        level: Short = 1,
        gold: Int = 10000,
        rice: Int = 10000,
        power: Int = 100,
        warState: Short = 0,
    ): Nation {
        return Nation(
            id = id,
            worldId = 1,
            name = "국가$id",
            color = "#FF0000",
            level = level,
            gold = gold,
            rice = rice,
            power = power,
            warState = warState,
        )
    }

    private fun createDiplomacy(
        srcNationId: Long,
        destNationId: Long,
        stateCode: String,
    ): Diplomacy {
        return Diplomacy(
            worldId = 1,
            srcNationId = srcNationId,
            destNationId = destNationId,
            stateCode = stateCode,
        )
    }

    private fun setupRepos(
        world: WorldState,
        general: General,
        city: City,
        nation: Nation?,
        allCities: List<City> = listOf(city),
        allGenerals: List<General> = listOf(general),
        allNations: List<Nation> = listOfNotNull(nation),
        diplomacies: List<Diplomacy> = emptyList(),
    ) {
        `when`(cityRepository.findById(general.cityId)).thenReturn(Optional.of(city))
        if (nation != null) {
            `when`(nationRepository.findById(general.nationId)).thenReturn(Optional.of(nation))
        }
        `when`(cityRepository.findByWorldId(world.id.toLong())).thenReturn(allCities)
        `when`(generalRepository.findByWorldId(world.id.toLong())).thenReturn(allGenerals)
        `when`(nationRepository.findByWorldId(world.id.toLong())).thenReturn(allNations)
        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong())).thenReturn(diplomacies)
    }

    // ========== Fallback to 휴식 ==========

    @Test
    fun `returns 휴식 when city not found`() {
        val world = createWorld()
        val general = createGeneral(cityId = 999)

        `when`(cityRepository.findById(999L)).thenReturn(Optional.empty())

        val action = ai.decideAndExecute(general, world)
        assertEquals("휴식", action)
    }

    // ========== Injury recovery ==========

    @Test
    fun `returns 요양 when general is injured during war`() {
        val world = createWorld()
        val general = createGeneral(injury = 10)
        val city = createCity(nationId = 1)
        val nation = createNation()
        val diplomacy = createDiplomacy(1, 2, "선전포고")

        setupRepos(world, general, city, nation, diplomacies = listOf(diplomacy),
            allNations = listOf(nation, createNation(id = 2)))

        val action = ai.decideAndExecute(general, world)
        assertEquals("요양", action)
    }

    @Test
    fun `returns 요양 when general is injured during peace`() {
        val world = createWorld()
        val general = createGeneral(injury = 5)
        val city = createCity(nationId = 1)
        val nation = createNation()

        setupRepos(world, general, city, nation)

        val action = ai.decideAndExecute(general, world)
        assertEquals("요양", action)
    }

    // ========== War actions ==========

    @Test
    fun `recruits when at war with low crew`() {
        val world = createWorld()
        val general = createGeneral(crew = 50, gold = 500)
        val city = createCity(nationId = 1)
        val nation = createNation(gold = 20000, rice = 20000)
        val diplomacy = createDiplomacy(1, 2, "선전포고")

        setupRepos(world, general, city, nation, diplomacies = listOf(diplomacy),
            allNations = listOf(nation, createNation(id = 2)))

        val action = ai.decideAndExecute(general, world)
        assertEquals("모병", action)
    }

    @Test
    fun `conscripts when at war with low crew and no gold`() {
        val world = createWorld()
        val general = createGeneral(crew = 50, gold = 10)
        val city = createCity(nationId = 1)
        val nation = createNation(gold = 20000, rice = 20000)
        val diplomacy = createDiplomacy(1, 2, "선전포고")

        setupRepos(world, general, city, nation, diplomacies = listOf(diplomacy),
            allNations = listOf(nation, createNation(id = 2)))

        val action = ai.decideAndExecute(general, world)
        assertEquals("징병", action)
    }

    @Test
    fun `trains when at war with low train`() {
        val world = createWorld()
        val general = createGeneral(crew = 2000, train = 50)
        val city = createCity(nationId = 1)
        val nation = createNation(gold = 20000, rice = 20000)
        val diplomacy = createDiplomacy(1, 2, "선전포고")

        setupRepos(world, general, city, nation, diplomacies = listOf(diplomacy),
            allNations = listOf(nation, createNation(id = 2)))

        val action = ai.decideAndExecute(general, world)
        assertEquals("훈련", action)
    }

    @Test
    fun `boosts morale when at war with low atmos`() {
        val world = createWorld()
        val general = createGeneral(crew = 2000, train = 80, atmos = 50)
        val city = createCity(nationId = 1)
        val nation = createNation(gold = 20000, rice = 20000)
        val diplomacy = createDiplomacy(1, 2, "선전포고")

        setupRepos(world, general, city, nation, diplomacies = listOf(diplomacy),
            allNations = listOf(nation, createNation(id = 2)))

        val action = ai.decideAndExecute(general, world)
        assertEquals("사기진작", action)
    }

    @Test
    fun `attacks from front city with enough troops`() {
        val world = createWorld()
        val general = createGeneral(crew = 2000, train = 80, atmos = 80)
        val city = createCity(nationId = 1, frontState = 1)
        val enemyCity = createCity(id = 2, nationId = 2, frontState = 0)
        val nation = createNation(gold = 20000, rice = 20000)
        val diplomacy = createDiplomacy(1, 2, "선전포고")

        setupRepos(world, general, city, nation,
            allCities = listOf(city, enemyCity),
            diplomacies = listOf(diplomacy),
            allNations = listOf(nation, createNation(id = 2)))

        val action = ai.decideAndExecute(general, world)
        assertEquals("출병", action)
    }

    // ========== Peace actions: city development ==========

    @Test
    fun `develops agriculture when city agri is low`() {
        val world = createWorld()
        val general = createGeneral(crew = 0, intel = 80, strength = 30, leadership = 30)
        val city = createCity(nationId = 1, agri = 100, agriMax = 1000)
        val nation = createNation()

        setupRepos(world, general, city, nation)

        val action = ai.decideAndExecute(general, world)
        assertEquals("농지개간", action)
    }

    @Test
    fun `develops commerce when agri is ok but comm is low`() {
        val world = createWorld()
        val general = createGeneral(crew = 0, intel = 80, strength = 30, leadership = 30)
        val city = createCity(nationId = 1, agri = 600, agriMax = 1000, comm = 100, commMax = 1000)
        val nation = createNation()

        setupRepos(world, general, city, nation)

        val action = ai.decideAndExecute(general, world)
        assertEquals("상업투자", action)
    }

    @Test
    fun `develops security when agri and comm are ok but secu is low`() {
        val world = createWorld()
        val general = createGeneral(crew = 0, intel = 80, strength = 30, leadership = 30)
        val city = createCity(nationId = 1, agri = 600, agriMax = 1000, comm = 600, commMax = 1000, secu = 100, secuMax = 1000)
        val nation = createNation()

        setupRepos(world, general, city, nation)

        val action = ai.decideAndExecute(general, world)
        assertEquals("치안강화", action)
    }

    // ========== Peace actions: type-based ==========

    @Test
    fun `warrior type trains troops during peace`() {
        val world = createWorld()
        // Warrior: strength >= leadership && strength >= intel
        val general = createGeneral(strength = 90, leadership = 50, intel = 30, crew = 500, train = 50)
        val city = createCity(nationId = 1, agri = 600, agriMax = 1000, comm = 600, commMax = 1000, secu = 600, secuMax = 1000)
        val nation = createNation()

        setupRepos(world, general, city, nation)

        val action = ai.decideAndExecute(general, world)
        assertEquals("훈련", action)
    }

    @Test
    fun `warrior type recruits when no crew`() {
        val world = createWorld()
        val general = createGeneral(strength = 90, leadership = 50, intel = 30, crew = 0)
        val city = createCity(nationId = 1, agri = 600, agriMax = 1000, comm = 600, commMax = 1000, secu = 600, secuMax = 1000)
        val nation = createNation()

        setupRepos(world, general, city, nation)

        val action = ai.decideAndExecute(general, world)
        // warrior with crew < 1000 and in owned city => 모병
        assertEquals("모병", action)
    }

    // ========== Chief (lord) actions ==========

    @Test
    fun `chief assigns unassigned generals during peace`() {
        val world = createWorld()
        val chief = createGeneral(id = 1, officerLevel = 12, crew = 2000)
        val unassigned = createGeneral(id = 2, officerLevel = 0, npcState = 2)
        val city = createCity(nationId = 1)
        val nation = createNation()

        setupRepos(world, chief, city, nation,
            allGenerals = listOf(chief, unassigned))

        val action = ai.decideAndExecute(chief, world)
        assertEquals("발령", action)
    }

    @Test
    fun `chief expands city when wealthy`() {
        val world = createWorld()
        val chief = createGeneral(id = 1, officerLevel = 12, crew = 2000)
        val assigned = createGeneral(id = 2, officerLevel = 3, npcState = 2)
        val city = createCity(nationId = 1)
        city.level = 3
        val nation = createNation(gold = 20000)

        setupRepos(world, chief, city, nation,
            allCities = listOf(city),
            allGenerals = listOf(chief, assigned))

        val action = ai.decideAndExecute(chief, world)
        assertEquals("증축", action)
    }

    // ========== Diplomacy state detection ==========

    @Test
    fun `detects AT_WAR from diplomacy 선전포고`() {
        val world = createWorld()
        val general = createGeneral(crew = 50, gold = 500)
        val city = createCity(nationId = 1)
        val nation = createNation(gold = 20000, rice = 20000)
        val diplomacy = createDiplomacy(1, 2, "선전포고")

        setupRepos(world, general, city, nation, diplomacies = listOf(diplomacy),
            allNations = listOf(nation, createNation(id = 2)))

        // If at war, injured generals rest, otherwise it decides a war action
        val action = ai.decideAndExecute(general, world)
        // Should pick a war action (모병 since crew < 100)
        assertEquals("모병", action)
    }

    @Test
    fun `detects PEACE when no diplomacy entries`() {
        val world = createWorld()
        val general = createGeneral(crew = 0, intel = 80, strength = 30, leadership = 30)
        val city = createCity(nationId = 1, agri = 100, agriMax = 1000)
        val nation = createNation()

        setupRepos(world, general, city, nation)

        // Peace + low agri => should develop
        val action = ai.decideAndExecute(general, world)
        assertEquals("농지개간", action)
    }

    // ========== NPC troop leader (npcState=5) ==========

    @Test
    fun `troop leader (npcState=5) always returns 집합`() {
        val world = createWorld()
        val general = createGeneral(npcState = 5)

        val action = ai.decideAndExecute(general, world)
        assertEquals("집합", action)
    }

    // ========== Wanderer (nationId=0) ==========

    @Test
    fun `wanderer returns 요양 when injured`() {
        val world = createWorld()
        val general = createGeneral(nationId = 0, injury = 10)

        val action = ai.decideAndExecute(general, world)
        assertEquals("요양", action)
    }

    @Test
    fun `wanderer returns one of exploration actions`() {
        val world = createWorld()
        val general = createGeneral(nationId = 0, injury = 0)

        val action = ai.decideAndExecute(general, world)
        assertTrue(action in listOf("견문", "이동", "물자조달", "휴식"),
            "Expected wanderer action but got: $action")
    }

    // ========== Reserved command ==========

    @Test
    fun `uses reserved command when set in meta`() {
        val world = createWorld()
        val general = createGeneral(crew = 0, intel = 80, strength = 30, leadership = 30)
        general.meta["reservedCommand"] = "단련"
        val city = createCity(nationId = 1)
        val nation = createNation()

        setupRepos(world, general, city, nation)

        val action = ai.decideAndExecute(general, world)
        assertEquals("단련", action)
        // Reserved command should be cleared after use
        assertNull(general.meta["reservedCommand"])
    }

    @Test
    fun `ignores reserved command when it is 휴식`() {
        val world = createWorld()
        val general = createGeneral(crew = 0, intel = 80, strength = 30, leadership = 30)
        general.meta["reservedCommand"] = "휴식"
        val city = createCity(nationId = 1, agri = 100, agriMax = 1000)
        val nation = createNation()

        setupRepos(world, general, city, nation)

        val action = ai.decideAndExecute(general, world)
        // Should fall through to normal AI decision (농지개간 since agri is low)
        assertEquals("농지개간", action)
    }

    // ========== Cure threshold ==========

    @Test
    fun `returns 요양 when injury exceeds cure threshold from policy`() {
        val world = createWorld()
        // Injury = 25, default cureThreshold = 20
        val general = createGeneral(injury = 25)
        val city = createCity(nationId = 1)
        val nation = createNation()

        setupRepos(world, general, city, nation)

        val action = ai.decideAndExecute(general, world)
        assertEquals("요양", action)
    }

    // ========== classifyGeneral: probabilistic hybrid ==========

    @Test
    fun `classifyGeneral sets WARRIOR for strength-dominant general`() {
        val general = createGeneral(strength = 90, intel = 30, leadership = 30)
        val flags = ai.classifyGeneral(general, Random(42))
        assertTrue(flags and GeneralType.WARRIOR.flag != 0,
            "Should have WARRIOR flag")
    }

    @Test
    fun `classifyGeneral sets STRATEGIST for intel-dominant general`() {
        val general = createGeneral(strength = 30, intel = 90, leadership = 30)
        val flags = ai.classifyGeneral(general, Random(42))
        assertTrue(flags and GeneralType.STRATEGIST.flag != 0,
            "Should have STRATEGIST flag")
    }

    @Test
    fun `classifyGeneral sets COMMANDER when leadership meets threshold`() {
        val general = createGeneral(strength = 50, intel = 50, leadership = 70)
        val flags = ai.classifyGeneral(general, Random(42), minNPCWarLeadership = 40)
        assertTrue(flags and GeneralType.COMMANDER.flag != 0,
            "Should have COMMANDER flag")
    }

    @Test
    fun `classifyGeneral does not set COMMANDER when leadership below threshold`() {
        val general = createGeneral(strength = 50, intel = 50, leadership = 30)
        val flags = ai.classifyGeneral(general, Random(42), minNPCWarLeadership = 40)
        assertTrue(flags and GeneralType.COMMANDER.flag == 0,
            "Should not have COMMANDER flag")
    }
}
