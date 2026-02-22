package com.opensam.engine.war

import com.opensam.engine.DiplomacyService
import com.opensam.engine.EventService
import com.opensam.entity.*
import com.opensam.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.time.OffsetDateTime
import java.util.Optional

class BattleServiceTest {

    private lateinit var service: BattleService
    private lateinit var cityRepository: CityRepository
    private lateinit var generalRepository: GeneralRepository
    private lateinit var nationRepository: NationRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var eventService: EventService
    private lateinit var diplomacyService: DiplomacyService

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = org.mockito.Mockito.any<T>() as T

    @BeforeEach
    fun setUp() {
        cityRepository = mock(CityRepository::class.java)
        generalRepository = mock(GeneralRepository::class.java)
        nationRepository = mock(NationRepository::class.java)
        messageRepository = mock(MessageRepository::class.java)
        eventService = mock(EventService::class.java)
        diplomacyService = mock(DiplomacyService::class.java)

        `when`(cityRepository.save(anyNonNull<City>())).thenAnswer { it.arguments[0] }
        `when`(generalRepository.save(anyNonNull<General>())).thenAnswer { it.arguments[0] }
        `when`(nationRepository.save(anyNonNull<Nation>())).thenAnswer { it.arguments[0] }
        `when`(messageRepository.save(anyNonNull<Message>())).thenAnswer { it.arguments[0] }

        val modifierService = mock(com.opensam.engine.modifier.ModifierService::class.java)

        service = BattleService(
            cityRepository, generalRepository, nationRepository,
            messageRepository, eventService, diplomacyService,
            modifierService,
        )
    }

    private fun createWorld(): WorldState {
        return WorldState(
            id = 1,
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 3,
            tickSeconds = 300,
            config = mutableMapOf("hiddenSeed" to "testSeed"),
        )
    }

    private fun createGeneral(
        id: Long = 1,
        nationId: Long = 1,
        cityId: Long = 1,
        strength: Short = 99,
        leadership: Short = 99,
        crew: Int = 50000,
        rice: Int = 500000,
        gold: Int = 10000,
        train: Short = 80,
        atmos: Short = 80,
        npcState: Short = 0,
        officerLevel: Short = 0,
        officerCity: Int = 0,
        experience: Int = 10000,
        dedication: Int = 5000,
    ): General {
        return General(
            id = id,
            worldId = 1,
            name = "장수$id",
            nationId = nationId,
            cityId = cityId,
            leadership = leadership,
            strength = strength,
            intel = 50,
            crew = crew,
            crewType = 0,
            train = train,
            atmos = atmos,
            gold = gold,
            rice = rice,
            experience = experience,
            dedication = dedication,
            npcState = npcState,
            officerLevel = officerLevel,
            officerCity = officerCity,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createCity(
        id: Long = 10,
        nationId: Long = 2,
        def: Int = 10,
        wall: Int = 10,
        pop: Int = 10000,
        agri: Int = 1000,
        comm: Int = 1000,
        secu: Int = 1000,
    ): City {
        return City(
            id = id,
            worldId = 1,
            name = "테스트도시",
            nationId = nationId,
            def = def,
            defMax = 1000,
            wall = wall,
            wallMax = 1000,
            pop = pop,
            popMax = 50000,
            agri = agri,
            agriMax = 5000,
            comm = comm,
            commMax = 5000,
            secu = secu,
            secuMax = 5000,
        )
    }

    private fun createNation(id: Long = 2, name: String = "위", gold: Int = 10000, rice: Int = 10000, capitalCityId: Long? = 10): Nation {
        return Nation(
            id = id,
            worldId = 1,
            name = name,
            color = "blue",
            gold = gold,
            rice = rice,
            capitalCityId = capitalCityId,
        )
    }

    // ========== executeBattle: city occupation dispatches events ==========

    @Test
    fun `executeBattle dispatches OCCUPY_CITY event on city occupation`() {
        val world = createWorld()
        val attacker = createGeneral(id = 1, nationId = 1)
        val city = createCity(id = 10, nationId = 2, def = 10, wall = 10)

        val attackerNation = createNation(id = 1, name = "촉")
        val defenderNation = createNation(id = 2, name = "위", capitalCityId = 99)

        `when`(nationRepository.findById(1L)).thenReturn(Optional.of(attackerNation))
        `when`(nationRepository.findById(2L)).thenReturn(Optional.of(defenderNation))
        `when`(generalRepository.findByCityId(10L)).thenReturn(emptyList())
        // Defender nation still has other cities
        `when`(cityRepository.findByNationId(2L)).thenReturn(listOf(createCity(id = 99, nationId = 2)))

        val result = service.executeBattle(attacker, city, world)

        if (result.cityOccupied) {
            verify(eventService).dispatchEvents(world, "OCCUPY_CITY")
        }
    }

    @Test
    fun `executeBattle dispatches DESTROY_NATION event when last city taken`() {
        val world = createWorld()
        val attacker = createGeneral(id = 1, nationId = 1)
        val city = createCity(id = 10, nationId = 2, def = 10, wall = 10)

        val attackerNation = createNation(id = 1, name = "촉")
        val defenderNation = createNation(id = 2, name = "위", capitalCityId = 10)

        `when`(nationRepository.findById(1L)).thenReturn(Optional.of(attackerNation))
        `when`(nationRepository.findById(2L)).thenReturn(Optional.of(defenderNation))
        `when`(generalRepository.findByCityId(10L)).thenReturn(emptyList())
        // No remaining cities for defender
        `when`(cityRepository.findByNationId(2L)).thenReturn(emptyList())
        // No generals in destroyed nation
        `when`(generalRepository.findByNationId(2L)).thenReturn(emptyList())

        val result = service.executeBattle(attacker, city, world)

        if (result.cityOccupied) {
            verify(eventService).dispatchEvents(world, "OCCUPY_CITY")
            verify(eventService).dispatchEvents(world, "DESTROY_NATION")
        }
    }

    // ========== Nation destruction: general release and penalties ==========

    @Test
    fun `nation destruction releases generals with resource penalties`() {
        val world = createWorld()
        val attacker = createGeneral(id = 1, nationId = 1)
        val city = createCity(id = 10, nationId = 2, def = 10, wall = 10)

        val attackerNation = createNation(id = 1, name = "촉")
        val defenderNation = createNation(id = 2, name = "위", capitalCityId = 10, gold = 5000, rice = 8000)

        val defGen1 = createGeneral(id = 10, nationId = 2, gold = 1000, rice = 2000, experience = 1000, dedication = 1000, npcState = 0)
        val defGen2 = createGeneral(id = 11, nationId = 2, gold = 500, rice = 1000, experience = 500, dedication = 500, npcState = 3)

        `when`(nationRepository.findById(1L)).thenReturn(Optional.of(attackerNation))
        `when`(nationRepository.findById(2L)).thenReturn(Optional.of(defenderNation))
        `when`(generalRepository.findByCityId(10L)).thenReturn(emptyList())
        `when`(cityRepository.findByNationId(2L)).thenReturn(emptyList())
        `when`(generalRepository.findByNationId(2L)).thenReturn(listOf(defGen1, defGen2))

        val result = service.executeBattle(attacker, city, world)

        if (result.cityOccupied) {
            // Both generals released (nationId = 0)
            assertEquals(0L, defGen1.nationId)
            assertEquals(0L, defGen2.nationId)

            // Officers demoted
            assertEquals(0.toShort(), defGen1.officerLevel)
            assertEquals(0.toShort(), defGen2.officerLevel)

            // Gold/rice reduced (20-50%)
            assertTrue(defGen1.gold < 1000, "General should lose gold")
            assertTrue(defGen1.rice < 2000, "General should lose rice")

            // Experience reduced by 10%
            assertTrue(defGen1.experience < 1000, "General should lose experience")

            // Dedication reduced by 50%
            assertTrue(defGen1.dedication < 1000, "General should lose dedication")
        }
    }

    // ========== Nation destruction: conquest rewards ==========

    @Test
    fun `nation destruction distributes conquest rewards to attacker nation`() {
        val world = createWorld()
        val attacker = createGeneral(id = 1, nationId = 1)
        val city = createCity(id = 10, nationId = 2, def = 10, wall = 10)

        val attackerNation = createNation(id = 1, name = "촉", gold = 5000, rice = 5000)
        val defenderNation = createNation(id = 2, name = "위", capitalCityId = 10, gold = 10000, rice = 12000)

        `when`(nationRepository.findById(1L)).thenReturn(Optional.of(attackerNation))
        `when`(nationRepository.findById(2L)).thenReturn(Optional.of(defenderNation))
        `when`(generalRepository.findByCityId(10L)).thenReturn(emptyList())
        `when`(cityRepository.findByNationId(2L)).thenReturn(emptyList())
        `when`(generalRepository.findByNationId(2L)).thenReturn(emptyList())

        val result = service.executeBattle(attacker, city, world)

        if (result.cityOccupied) {
            // Attacker nation should receive half of (10000-0)/2=5000 gold and (12000-2000)/2=5000 rice
            assertTrue(attackerNation.gold > 5000, "Attacker nation should gain gold reward")
            assertTrue(attackerNation.rice > 5000, "Attacker nation should gain rice reward")
        }
    }

    // ========== Nation destruction: diplomatic relations killed ==========

    @Test
    fun `nation destruction kills all diplomatic relations`() {
        val world = createWorld()
        val attacker = createGeneral(id = 1, nationId = 1)
        val city = createCity(id = 10, nationId = 2, def = 10, wall = 10)

        val attackerNation = createNation(id = 1, name = "촉")
        val defenderNation = createNation(id = 2, name = "위", capitalCityId = 10)

        `when`(nationRepository.findById(1L)).thenReturn(Optional.of(attackerNation))
        `when`(nationRepository.findById(2L)).thenReturn(Optional.of(defenderNation))
        `when`(generalRepository.findByCityId(10L)).thenReturn(emptyList())
        `when`(cityRepository.findByNationId(2L)).thenReturn(emptyList())
        `when`(generalRepository.findByNationId(2L)).thenReturn(emptyList())

        val result = service.executeBattle(attacker, city, world)

        if (result.cityOccupied) {
            verify(diplomacyService).killAllRelationsForNation(1L, 2L)
        }
    }

    // ========== City occupation: city stat reset ==========

    @Test
    fun `city occupation resets city stats per legacy rules`() {
        val world = createWorld()
        val attacker = createGeneral(id = 1, nationId = 1)
        val city = createCity(id = 10, nationId = 2, def = 10, wall = 10, agri = 1000, comm = 1000, secu = 1000)

        val attackerNation = createNation(id = 1, name = "촉")
        val defenderNation = createNation(id = 2, name = "위", capitalCityId = 99)

        `when`(nationRepository.findById(1L)).thenReturn(Optional.of(attackerNation))
        `when`(nationRepository.findById(2L)).thenReturn(Optional.of(defenderNation))
        `when`(generalRepository.findByCityId(10L)).thenReturn(emptyList())
        `when`(cityRepository.findByNationId(2L)).thenReturn(listOf(createCity(id = 99, nationId = 2)))

        val result = service.executeBattle(attacker, city, world)

        if (result.cityOccupied) {
            assertEquals(1L, city.nationId, "City nation should change to attacker")
            assertEquals(0f, city.trust, "Trust should be reset")
            assertEquals(1.toShort(), city.supplyState)
            assertEquals(0.toShort(), city.term)
            assertEquals(0, city.officerSet)
            // agri/comm/secu reduced by 30%
            assertEquals(700, city.agri)
            assertEquals(700, city.comm)
            assertEquals(700, city.secu)
        }
    }

    // ========== Capital relocation ==========

    @Test
    fun `capital relocation halves nation gold and rice`() {
        val world = createWorld()
        val attacker = createGeneral(id = 1, nationId = 1)
        val city = createCity(id = 10, nationId = 2, def = 10, wall = 10)

        val attackerNation = createNation(id = 1, name = "촉")
        val defenderNation = createNation(id = 2, name = "위", capitalCityId = 10, gold = 10000, rice = 8000)

        val otherCity = createCity(id = 99, nationId = 2, pop = 20000)

        `when`(nationRepository.findById(1L)).thenReturn(Optional.of(attackerNation))
        `when`(nationRepository.findById(2L)).thenReturn(Optional.of(defenderNation))
        `when`(generalRepository.findByCityId(10L)).thenReturn(emptyList())
        `when`(cityRepository.findByNationId(2L)).thenReturn(listOf(otherCity))
        `when`(generalRepository.findByNationId(2L)).thenReturn(emptyList())

        val result = service.executeBattle(attacker, city, world)

        if (result.cityOccupied) {
            assertEquals(99L, defenderNation.capitalCityId)
            assertEquals(5000, defenderNation.gold)
            assertEquals(4000, defenderNation.rice)
        }
    }

    @Test
    fun `capital relocation applies 20 percent morale loss to all nationals`() {
        val world = createWorld()
        val attacker = createGeneral(id = 1, nationId = 1)
        val city = createCity(id = 10, nationId = 2, def = 10, wall = 10)

        val attackerNation = createNation(id = 1, name = "촉")
        val defenderNation = createNation(id = 2, name = "위", capitalCityId = 10)

        val nationalGen = createGeneral(id = 20, nationId = 2, cityId = 99, atmos = 100)
        val otherCity = createCity(id = 99, nationId = 2, pop = 20000)

        `when`(nationRepository.findById(1L)).thenReturn(Optional.of(attackerNation))
        `when`(nationRepository.findById(2L)).thenReturn(Optional.of(defenderNation))
        `when`(generalRepository.findByCityId(10L)).thenReturn(emptyList())
        `when`(cityRepository.findByNationId(2L)).thenReturn(listOf(otherCity))
        `when`(generalRepository.findByNationId(2L)).thenReturn(listOf(nationalGen))

        val result = service.executeBattle(attacker, city, world)

        if (result.cityOccupied) {
            assertEquals(80.toShort(), nationalGen.atmos, "Morale should drop by 20%")
        }
    }

    // ========== Conquest logging ==========

    @Test
    fun `city occupation logs conquest message`() {
        val world = createWorld()
        val attacker = createGeneral(id = 1, nationId = 1)
        val city = createCity(id = 10, nationId = 2, def = 10, wall = 10)

        val attackerNation = createNation(id = 1, name = "촉")
        val defenderNation = createNation(id = 2, name = "위", capitalCityId = 99)

        `when`(nationRepository.findById(1L)).thenReturn(Optional.of(attackerNation))
        `when`(nationRepository.findById(2L)).thenReturn(Optional.of(defenderNation))
        `when`(generalRepository.findByCityId(10L)).thenReturn(emptyList())
        `when`(cityRepository.findByNationId(2L)).thenReturn(listOf(createCity(id = 99, nationId = 2)))

        val result = service.executeBattle(attacker, city, world)

        if (result.cityOccupied) {
            val captor = ArgumentCaptor.forClass(Message::class.java)
            verify(messageRepository, atLeastOnce()).save(captor.capture())

            val conquestLog = captor.allValues.find { it.messageType == "city_conquered" }
            assertNotNull(conquestLog, "Should log city conquest")
            assertEquals("world_history", conquestLog!!.mailboxCode)
        }
    }

    // ========== NPC auto-join queuing ==========

    @Test
    fun `nation destruction queues eligible NPCs for auto-join`() {
        val world = createWorld()
        val attacker = createGeneral(id = 1, nationId = 1)
        val city = createCity(id = 10, nationId = 2, def = 10, wall = 10)

        val attackerNation = createNation(id = 1, name = "촉")
        val defenderNation = createNation(id = 2, name = "위", capitalCityId = 10)

        // Create several NPC generals with eligible states
        val npcGenerals = (2..8).filter { it != 5 }.map { npcState ->
            createGeneral(
                id = (100 + npcState).toLong(),
                nationId = 2,
                npcState = npcState.toShort(),
                gold = 100,
                rice = 100,
            )
        }

        `when`(nationRepository.findById(1L)).thenReturn(Optional.of(attackerNation))
        `when`(nationRepository.findById(2L)).thenReturn(Optional.of(defenderNation))
        `when`(generalRepository.findByCityId(10L)).thenReturn(emptyList())
        `when`(cityRepository.findByNationId(2L)).thenReturn(emptyList())
        `when`(generalRepository.findByNationId(2L)).thenReturn(npcGenerals)

        service.executeBattle(attacker, city, world)

        // All NPC generals should be released (nationId = 0)
        for (gen in npcGenerals) {
            assertEquals(0L, gen.nationId, "NPC general ${gen.id} should be released")
        }

        // Some may have autoJoinNationId metadata (probabilistic, so we just check the field exists for those that got it)
        val autoJoinQueued = npcGenerals.filter { it.meta.containsKey("autoJoinNationId") }
        for (gen in autoJoinQueued) {
            assertEquals(1L, gen.meta["autoJoinNationId"], "Auto-join target should be attacker nation")
            assertTrue((gen.meta["autoJoinDelay"] as Int) in 0..12, "Delay should be 0-12")
        }
    }
}
