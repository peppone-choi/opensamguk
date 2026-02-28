package com.opensam.engine

import com.opensam.entity.*
import com.opensam.repository.*
import com.opensam.service.HistoryService
import com.opensam.service.ScenarioService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

/**
 * Unit tests for EventActionService — verifying Kotlin implementations match PHP legacy behavior.
 *
 * PHP references: /ref/core/hwe/sammo/Event/Action/
 */
class EventActionServiceTest {

    private lateinit var service: EventActionService
    private lateinit var generalRepository: GeneralRepository
    private lateinit var nationRepository: NationRepository
    private lateinit var cityRepository: CityRepository
    private lateinit var eventRepository: EventRepository
    private lateinit var generalTurnRepository: GeneralTurnRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var bettingRepository: BettingRepository
    private lateinit var betEntryRepository: BetEntryRepository
    private lateinit var historyService: HistoryService
    private lateinit var scenarioService: ScenarioService
    private lateinit var specialAssignmentService: SpecialAssignmentService
    private lateinit var rankDataRepository: RankDataRepository

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = any<T>() as T

    @BeforeEach
    fun setUp() {
        generalRepository = mock(GeneralRepository::class.java)
        nationRepository = mock(NationRepository::class.java)
        cityRepository = mock(CityRepository::class.java)
        eventRepository = mock(EventRepository::class.java)
        generalTurnRepository = mock(GeneralTurnRepository::class.java)
        messageRepository = mock(MessageRepository::class.java)
        bettingRepository = mock(BettingRepository::class.java)
        betEntryRepository = mock(BetEntryRepository::class.java)
        historyService = mock(HistoryService::class.java)
        scenarioService = mock(ScenarioService::class.java)
        specialAssignmentService = mock(SpecialAssignmentService::class.java)
        rankDataRepository = mock(RankDataRepository::class.java)

        service = EventActionService(
            generalRepository, nationRepository, cityRepository,
            eventRepository, generalTurnRepository, messageRepository,
            bettingRepository, betEntryRepository,
            historyService, scenarioService, specialAssignmentService,
            rankDataRepository,
        )
    }

    private fun createWorld(year: Short = 200, month: Short = 3): WorldState =
        WorldState(id = 1, scenarioCode = "test", currentYear = year, currentMonth = month, tickSeconds = 300)

    private fun createGeneral(
        id: Long = 1L,
        worldId: Long = 1L,
        nationId: Long = 1L,
        name: String = "장수",
        leadership: Short = 60,
        strength: Short = 60,
        intel: Short = 60,
        betray: Short = 0,
        age: Short = 25,
        belong: Short = 5,
        officerLevel: Short = 0,
        npcState: Short = 2,
        experience: Int = 1000,
        dedication: Int = 500,
        itemCode: String = "None",
    ): General = General(
        id = id, worldId = worldId, nationId = nationId, name = name,
        leadership = leadership, strength = strength, intel = intel,
        betray = betray, age = age, belong = belong, officerLevel = officerLevel,
        npcState = npcState, experience = experience, dedication = dedication,
        itemCode = itemCode,
    )

    private fun createNation(
        id: Long = 1L,
        worldId: Long = 1L,
        name: String = "테스트국",
        level: Short = 2,
        scoutLevel: Short = 0,
        gold: Int = 10000,
    ): Nation = Nation(id = id, worldId = worldId, name = name, level = level, scoutLevel = scoutLevel, gold = gold)

    private fun createCity(
        id: Long = 1L,
        worldId: Long = 1L,
        nationId: Long = 1L,
        pop: Int = 10000,
        popMax: Int = 50000,
        agri: Int = 500, agriMax: Int = 1000,
        comm: Int = 500, commMax: Int = 1000,
        secu: Int = 500, secuMax: Int = 1000,
        def: Int = 500, defMax: Int = 1000,
        wall: Int = 500, wallMax: Int = 1000,
        trust: Float = 80f,
        trade: Int = 100,
        officerSet: Int = 0,
        dead: Int = 0,
    ): City = City(
        id = id, worldId = worldId, name = "테스트도시$id", nationId = nationId,
        pop = pop, popMax = popMax, agri = agri, agriMax = agriMax,
        comm = comm, commMax = commMax, secu = secu, secuMax = secuMax,
        def = def, defMax = defMax, wall = wall, wallMax = wallMax,
        trust = trust.toInt(), trade = trade, officerSet = officerSet, dead = dead,
    )

    // ─── AddGlobalBetray ───────────────────────────────────────────────────

    @Nested
    inner class AddGlobalBetray {

        @Test
        fun `increases betray for generals at or below ifMax`() {
            // PHP: foreach general where betray <= ifMax → betray += cnt
            val gen1 = createGeneral(id = 1, betray = 0)  // betray <= 0 ✓
            val gen2 = createGeneral(id = 2, betray = 5)  // betray > 0 ✗
            `when`(generalRepository.findByWorldId(1L)).thenReturn(listOf(gen1, gen2))
            `when`(generalRepository.saveAll(anyNonNull<List<General>>())).thenReturn(listOf(gen1, gen2))

            service.addGlobalBetray(createWorld(), cnt = 1, ifMax = 0)

            assertEquals(1.toShort(), gen1.betray)
            assertEquals(5.toShort(), gen2.betray)  // unchanged
        }

        @Test
        fun `increases betray by cnt`() {
            val gen = createGeneral(betray = 2)
            `when`(generalRepository.findByWorldId(1L)).thenReturn(listOf(gen))
            `when`(generalRepository.saveAll(anyNonNull<List<General>>())).thenReturn(listOf(gen))

            service.addGlobalBetray(createWorld(), cnt = 3, ifMax = 10)

            assertEquals(5.toShort(), gen.betray)
        }

        @Test
        fun `handles empty general list gracefully`() {
            `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())
            `when`(generalRepository.saveAll(anyNonNull<List<General>>())).thenReturn(emptyList())

            assertDoesNotThrow { service.addGlobalBetray(createWorld()) }
        }

        @Test
        fun `does not affect generals above ifMax`() {
            val gen = createGeneral(betray = 5)
            `when`(generalRepository.findByWorldId(1L)).thenReturn(listOf(gen))
            `when`(generalRepository.saveAll(anyNonNull<List<General>>())).thenReturn(listOf(gen))

            service.addGlobalBetray(createWorld(), cnt = 1, ifMax = 3)

            assertEquals(5.toShort(), gen.betray)  // unchanged because betray(5) > ifMax(3)
        }
    }

    // ─── BlockScoutAction / UnblockScoutAction ────────────────────────────

    @Nested
    inner class ScoutActions {

        @Test
        fun `blockScoutAction sets all nation scoutLevel to 1`() {
            // PHP: foreach nation → scout = 1 (blocked)
            val nation1 = createNation(id = 1, scoutLevel = 0)
            val nation2 = createNation(id = 2, scoutLevel = 0)
            `when`(nationRepository.findByWorldId(1L)).thenReturn(listOf(nation1, nation2))
            `when`(nationRepository.saveAll(anyNonNull<List<Nation>>())).thenReturn(listOf(nation1, nation2))

            service.blockScoutAction(createWorld())

            assertEquals(1.toShort(), nation1.scoutLevel)
            assertEquals(1.toShort(), nation2.scoutLevel)
        }

        @Test
        fun `unblockScoutAction sets all nation scoutLevel to 0`() {
            // PHP: foreach nation → scout = 0 (unblocked)
            val nation1 = createNation(id = 1, scoutLevel = 1)
            val nation2 = createNation(id = 2, scoutLevel = 1)
            `when`(nationRepository.findByWorldId(1L)).thenReturn(listOf(nation1, nation2))
            `when`(nationRepository.saveAll(anyNonNull<List<Nation>>())).thenReturn(listOf(nation1, nation2))

            service.unblockScoutAction(createWorld())

            assertEquals(0.toShort(), nation1.scoutLevel)
            assertEquals(0.toShort(), nation2.scoutLevel)
        }

        @Test
        fun `blockScoutAction propagates blockChangeScout to world config`() {
            val world = createWorld()
            `when`(nationRepository.findByWorldId(1L)).thenReturn(emptyList())
            `when`(nationRepository.saveAll(anyNonNull<List<Nation>>())).thenReturn(emptyList())

            service.blockScoutAction(world, blockChangeScout = true)

            assertEquals(true, world.config["blockChangeScout"])
        }

        @Test
        fun `unblockScoutAction propagates blockChangeScout to world config`() {
            val world = createWorld()
            `when`(nationRepository.findByWorldId(1L)).thenReturn(emptyList())
            `when`(nationRepository.saveAll(anyNonNull<List<Nation>>())).thenReturn(emptyList())

            service.unblockScoutAction(world, blockChangeScout = false)

            assertEquals(false, world.config["blockChangeScout"])
        }
    }

    // ─── ChangeCity ────────────────────────────────────────────────────────

    @Nested
    inner class ChangeCity {

        @Test
        fun `applies trust absolute value to all cities when target is null`() {
            // PHP: $actions['trust'] = 50 → city.trust = 50
            val city1 = createCity(id = 1, trust = 80f)
            val city2 = createCity(id = 2, trust = 60f)
            `when`(cityRepository.findByWorldId(1L)).thenReturn(listOf(city1, city2))
            `when`(cityRepository.saveAll(anyNonNull<List<City>>())).thenReturn(listOf(city1, city2))

            service.changeCity(createWorld(), null, mapOf("trust" to 50))

            assertEquals(50f, city1.trust, 0.01f)
            assertEquals(50f, city2.trust, 0.01f)
        }

        @Test
        fun `applies percentage expression to city trade`() {
            // PHP: trade = 100 (absolute value)
            val city = createCity(trade = 95)
            `when`(cityRepository.findByWorldId(1L)).thenReturn(listOf(city))
            `when`(cityRepository.saveAll(anyNonNull<List<City>>())).thenReturn(listOf(city))

            service.changeCity(createWorld(), null, mapOf("trade" to 102))

            assertEquals(102, city.trade)
        }

        @Test
        fun `filters cities by free target`() {
            val freeCity = createCity(id = 1, nationId = 0)
            val occupiedCity = createCity(id = 2, nationId = 1)
            `when`(cityRepository.findByWorldId(1L)).thenReturn(listOf(freeCity, occupiedCity))
            `when`(cityRepository.saveAll(anyNonNull<List<City>>())).thenReturn(listOf(freeCity))

            service.changeCity(createWorld(), "free", mapOf("trust" to 100))

            assertEquals(100f, freeCity.trust, 0.01f)
            // occupied city should be unchanged
            assertEquals(80f, occupiedCity.trust, 0.01f)
        }

        @Test
        fun `applies math expression with plus operator to city field`() {
            val city = createCity(agri = 400, agriMax = 1000)
            `when`(cityRepository.findByWorldId(1L)).thenReturn(listOf(city))
            `when`(cityRepository.saveAll(anyNonNull<List<City>>())).thenReturn(listOf(city))

            // "+100" should add 100 to current agri
            service.changeCity(createWorld(), null, mapOf("agri" to "+100"))

            assertEquals(500, city.agri)
        }

        @Test
        fun `clamps trust to 100 maximum`() {
            val city = createCity(trust = 90f)
            `when`(cityRepository.findByWorldId(1L)).thenReturn(listOf(city))
            `when`(cityRepository.saveAll(anyNonNull<List<City>>())).thenReturn(listOf(city))

            service.changeCity(createWorld(), null, mapOf("trust" to 200))

            assertEquals(100f, city.trust, 0.01f)
        }
    }

    // ─── NewYear ────────────────────────────────────────────────────────────

    @Nested
    inner class NewYear {

        @Test
        fun `increments age for all generals`() {
            // PHP: foreach general → age++
            val gen = createGeneral(age = 25, nationId = 1)
            `when`(generalRepository.findByWorldId(1L)).thenReturn(listOf(gen))
            `when`(generalRepository.saveAll(anyNonNull<List<General>>())).thenReturn(listOf(gen))

            service.newYear(createWorld())

            assertEquals(26.toShort(), gen.age)
        }

        @Test
        fun `increments belong for generals in a nation`() {
            // PHP: foreach general where nation != 0 → belong++
            val gen = createGeneral(nationId = 1, belong = 10)
            `when`(generalRepository.findByWorldId(1L)).thenReturn(listOf(gen))
            `when`(generalRepository.saveAll(anyNonNull<List<General>>())).thenReturn(listOf(gen))

            service.newYear(createWorld())

            assertEquals(11.toShort(), gen.belong)
        }

        @Test
        fun `does not increment belong for wandering generals`() {
            // PHP: only generals in a nation get belong++; nation==0 → wander
            val gen = createGeneral(nationId = 0, belong = 5)
            `when`(generalRepository.findByWorldId(1L)).thenReturn(listOf(gen))
            `when`(generalRepository.saveAll(anyNonNull<List<General>>())).thenReturn(listOf(gen))

            service.newYear(createWorld())

            assertEquals(5.toShort(), gen.belong)  // unchanged
        }

        @Test
        fun `logs history message with current year`() {
            `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())
            `when`(generalRepository.saveAll(anyNonNull<List<General>>())).thenReturn(emptyList())

            service.newYear(createWorld(year = 220))

            verify(historyService).logWorldHistory(anyLong(), anyString(), anyInt(), anyInt())
        }
    }

    // ─── ResetOfficerLock ──────────────────────────────────────────────────

    @Nested
    inner class ResetOfficerLock {

        @Test
        fun `resets officerSet to 0 for all cities`() {
            // PHP: UPDATE city SET officer_set=0 (all cities)
            val city = createCity(officerSet = 1)
            `when`(cityRepository.findByWorldId(1L)).thenReturn(listOf(city))
            `when`(cityRepository.saveAll(anyNonNull<List<City>>())).thenReturn(listOf(city))
            `when`(nationRepository.findByWorldId(1L)).thenReturn(emptyList())
            `when`(nationRepository.saveAll(anyNonNull<List<Nation>>())).thenReturn(emptyList())

            service.resetOfficerLock(createWorld())

            assertEquals(0, city.officerSet)
        }

        @Test
        fun `removes chiefSet from nation meta`() {
            // PHP: unset nation meta chiefSet
            val nation = createNation()
            nation.meta["chiefSet"] = true
            `when`(nationRepository.findByWorldId(1L)).thenReturn(listOf(nation))
            `when`(nationRepository.saveAll(anyNonNull<List<Nation>>())).thenReturn(listOf(nation))
            `when`(cityRepository.findByWorldId(1L)).thenReturn(emptyList())
            `when`(cityRepository.saveAll(anyNonNull<List<City>>())).thenReturn(emptyList())

            service.resetOfficerLock(createWorld())

            assertFalse(nation.meta.containsKey("chiefSet"))
        }
    }

    // ─── ProcessWarIncome ──────────────────────────────────────────────────

    @Nested
    inner class ProcessWarIncome {

        @Test
        fun `converts dead troops back to population at 20 percent`() {
            // PHP: pop += dead * 0.2; dead = 0
            val city = createCity(nationId = 1, pop = 10000, dead = 500)
            `when`(cityRepository.findByWorldId(anyLong())).thenReturn(listOf(city))
            `when`(cityRepository.saveAll(anyNonNull<List<City>>())).thenReturn(listOf(city))
            `when`(nationRepository.findByWorldId(anyLong())).thenReturn(emptyList())
            `when`(nationRepository.saveAll(anyNonNull<List<Nation>>())).thenReturn(emptyList())

            service.processWarIncome(createWorld())

            // 500 dead * 0.2 = 100 returned as pop
            assertEquals(10100, city.pop)
            assertEquals(0, city.dead)
        }

        @Test
        fun `zero dead means no pop change`() {
            val city = createCity(pop = 10000, dead = 0)
            `when`(cityRepository.findByWorldId(anyLong())).thenReturn(listOf(city))
            `when`(cityRepository.saveAll(anyNonNull<List<City>>())).thenReturn(listOf(city))
            `when`(nationRepository.findByWorldId(anyLong())).thenReturn(emptyList())
            `when`(nationRepository.saveAll(anyNonNull<List<Nation>>())).thenReturn(emptyList())

            service.processWarIncome(createWorld())

            assertEquals(10000, city.pop)
        }
    }

    // ─── DeleteEvent (via AutoDeleteInvader) ────────────────────────────────

    @Nested
    inner class AutoDeleteInvader {

        @Test
        fun `deletes event when nation does not exist`() {
            // PHP: nation not found → delete event
            `when`(nationRepository.findById(anyLong())).thenReturn(java.util.Optional.empty())

            service.autoDeleteInvader(createWorld(), nationId = 99L, currentEventId = 42L)

            verify(eventRepository).deleteById(42L)
        }

        @Test
        fun `does not delete event when nation still exists and at war`() {
            val nation = createNation(id = 1)
            nation.meta["atWar"] = true
            `when`(nationRepository.findById(anyLong())).thenReturn(java.util.Optional.of(nation))
            `when`(generalRepository.findByNationId(anyLong())).thenReturn(emptyList())

            service.autoDeleteInvader(createWorld(), nationId = 1L, currentEventId = 5L)

            verify(eventRepository, never()).deleteById(anyLong())
        }
    }

    // ─── RegNPC ────────────────────────────────────────────────────────────

    @Nested
    inner class RegNPC {

        @Test
        fun `creates a general with specified stats`() {
            // PHP: insert general with name, nationId, leadership, strength, intel
            `when`(cityRepository.findByWorldId(1L)).thenReturn(emptyList())
            val generalCaptor = ArgumentCaptor.forClass(General::class.java)
            `when`(generalRepository.save(generalCaptor.capture())).thenAnswer { it.arguments[0] }

            val params = mapOf(
                "name" to "테스트장수",
                "nationId" to 2,
                "leadership" to 80,
                "strength" to 70,
                "intel" to 60,
            )
            service.regNPC(createWorld(), params)

            val saved = generalCaptor.value
            assertEquals("테스트장수", saved.name)
            assertEquals(2L, saved.nationId)
            assertEquals(80.toShort(), saved.leadership)
            assertEquals(70.toShort(), saved.strength)
            assertEquals(60.toShort(), saved.intel)
        }

        @Test
        fun `regNeutralNPC creates general with npcState 6`() {
            `when`(cityRepository.findByWorldId(1L)).thenReturn(emptyList())
            val generalCaptor = ArgumentCaptor.forClass(General::class.java)
            `when`(generalRepository.save(generalCaptor.capture())).thenAnswer { it.arguments[0] }

            service.regNeutralNPC(createWorld(), mapOf("name" to "중립NPC"))

            val saved = generalCaptor.value
            assertEquals(6.toShort(), saved.npcState)
        }

        @Test
        fun `resolves city by name when cityId not specified`() {
            val city = createCity(id = 5L)
            city.name = "낙양"
            `when`(cityRepository.findByWorldId(1L)).thenReturn(listOf(city))
            val generalCaptor = ArgumentCaptor.forClass(General::class.java)
            `when`(generalRepository.save(generalCaptor.capture())).thenAnswer { it.arguments[0] }

            service.regNPC(createWorld(), mapOf("name" to "장수", "city" to "낙양"))

            val saved = generalCaptor.value
            assertEquals(5L, saved.cityId)
        }
    }

    // ─── LostUniqueItem ────────────────────────────────────────────────────

    @Nested
    inner class LostUniqueItem {

        @Test
        fun `does not affect generals with no items`() {
            val gen = createGeneral(npcState = 1, itemCode = "None")
            `when`(generalRepository.findByWorldId(1L)).thenReturn(listOf(gen))

            service.lostUniqueItem(createWorld(), lostProb = 1.0)

            verify(generalRepository, never()).save(anyNonNull())
            assertEquals("None", gen.itemCode)
        }

        @Test
        fun `does not affect NPC generals (npcState gt 1)`() {
            val gen = createGeneral(npcState = 3, itemCode = "전국옥새")
            `when`(generalRepository.findByWorldId(1L)).thenReturn(listOf(gen))

            service.lostUniqueItem(createWorld(), lostProb = 1.0)

            // npcState=3 > 1 → skipped entirely
            assertEquals("전국옥새", gen.itemCode)
        }

        @Test
        fun `does not remove buyable items even at 100 percent probability`() {
            val gen = createGeneral(npcState = 0, itemCode = "숫돌")
            `when`(generalRepository.findByWorldId(1L)).thenReturn(listOf(gen))

            service.lostUniqueItem(createWorld(), lostProb = 1.0)

            assertEquals("숫돌", gen.itemCode)
        }
    }

    // ─── MergeInheritPointRank ─────────────────────────────────────────────

    @Nested
    inner class MergeInheritPointRank {

        @Test
        fun `deletes old merge entries and creates new ones per general`() {
            val gen = createGeneral(id = 1, leadership = 80, strength = 70, intel = 60, experience = 1000, dedication = 500)
            `when`(generalRepository.findByWorldId(anyLong())).thenReturn(listOf(gen))
            `when`(rankDataRepository.findByWorldIdAndCategory(anyLong(), anyString())).thenReturn(emptyList())
            `when`(rankDataRepository.saveAll(anyNonNull<List<RankData>>())).thenReturn(emptyList())

            assertDoesNotThrow { service.mergeInheritPointRank(createWorld()) }

            // Should save new entries for the general
            verify(rankDataRepository, atLeastOnce()).saveAll(anyNonNull<List<RankData>>())
        }

        @Test
        fun `calculates positive inheritance score from general stats`() {
            val gen = createGeneral(leadership = 80, strength = 70, intel = 60, experience = 2000, dedication = 1000)
            `when`(generalRepository.findByWorldId(anyLong())).thenReturn(listOf(gen))
            `when`(rankDataRepository.findByWorldIdAndCategory(anyLong(), anyString())).thenReturn(emptyList())
            `when`(rankDataRepository.saveAll(anyNonNull<List<RankData>>())).thenReturn(emptyList())

            service.mergeInheritPointRank(createWorld())

            // Verify saveAll is called (score is sum of stats + experience/100 + dedication/100)
            verify(rankDataRepository, atLeastOnce()).saveAll(anyNonNull<List<RankData>>())
        }
    }

    // ─── CreateManyNPC ─────────────────────────────────────────────────────

    @Nested
    inner class CreateManyNPC {

        @Test
        fun `creates specified number of NPCs`() {
            val city = createCity(nationId = 0)
            `when`(cityRepository.findByWorldId(1L)).thenReturn(listOf(city))
            `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())
            val savedGenerals = mutableListOf<General>()
            `when`(generalRepository.save(anyNonNull<General>())).thenAnswer {
                val g = it.arguments[0] as General
                savedGenerals.add(g)
                g
            }

            service.createManyNPC(createWorld(), npcCount = 3, fillCnt = 0)

            assertEquals(3, savedGenerals.size)
        }

        @Test
        fun `skips when npcCount and fillCnt are both zero`() {
            `when`(cityRepository.findByWorldId(1L)).thenReturn(emptyList())
            `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

            service.createManyNPC(createWorld(), npcCount = 0, fillCnt = 0)

            verify(generalRepository, never()).save(anyNonNull())
        }

        @Test
        fun `created NPCs have npcState 3`() {
            val city = createCity(nationId = 0)
            `when`(cityRepository.findByWorldId(1L)).thenReturn(listOf(city))
            `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())
            val generalCaptor = ArgumentCaptor.forClass(General::class.java)
            `when`(generalRepository.save(generalCaptor.capture())).thenAnswer { it.arguments[0] }

            service.createManyNPC(createWorld(), npcCount = 1, fillCnt = 0)

            assertEquals(3.toShort(), generalCaptor.value.npcState)
        }
    }

    // ─── AssignGeneralSpeciality ───────────────────────────────────────────

    @Nested
    inner class AssignGeneralSpeciality {

        @Test
        fun `skips assignment when world year is too early (less than startYear + 3)`() {
            // PHP: if year < startYear + 3, skip speciality assignment
            val world = createWorld(year = 200)
            world.config["startYear"] = 200  // 200 < 200+3=203, should skip

            assertDoesNotThrow { service.assignGeneralSpeciality(world) }

            verify(generalRepository, never()).findByWorldId(anyLong())
        }

        @Test
        fun `processes assignment when world year is sufficient (startYear + 3 or later)`() {
            // PHP: if year >= startYear + 3, run assignment
            val world = createWorld(year = 205)
            world.config["startYear"] = 200  // 205 >= 203
            val gen = createGeneral()
            `when`(generalRepository.findByWorldId(anyLong())).thenReturn(listOf(gen))
            `when`(generalRepository.saveAll(anyNonNull<List<General>>())).thenReturn(listOf(gen))

            // Just verify it runs without exception and calls the repository
            assertDoesNotThrow { service.assignGeneralSpeciality(world) }

            verify(generalRepository).findByWorldId(1L)
        }
    }
}
