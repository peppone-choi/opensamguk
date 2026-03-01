package com.opensam.engine

import com.fasterxml.jackson.databind.ObjectMapper
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.OffsetDateTime
import java.util.*

class YearbookServiceTest {

    private lateinit var service: YearbookService
    private lateinit var worldStateRepository: WorldStateRepository
    private lateinit var cityRepository: CityRepository
    private lateinit var nationRepository: NationRepository
    private lateinit var generalRepository: GeneralRepository
    private lateinit var yearbookHistoryRepository: YearbookHistoryRepository

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = any<T>() as T

    @BeforeEach
    fun setUp() {
        worldStateRepository = mock(WorldStateRepository::class.java)
        cityRepository = mock(CityRepository::class.java)
        nationRepository = mock(NationRepository::class.java)
        generalRepository = mock(GeneralRepository::class.java)
        yearbookHistoryRepository = mock(YearbookHistoryRepository::class.java)

        service = YearbookService(
            worldStateRepository,
            cityRepository,
            nationRepository,
            generalRepository,
            yearbookHistoryRepository,
            ObjectMapper(),
        )
    }

    private fun createWorld(): WorldState {
        return WorldState(
            id = 1,
            name = "테스트서버",
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 6,
            tickSeconds = 300,
            config = mutableMapOf("startYear" to 184),
            meta = mutableMapOf(),
        )
    }

    @Test
    fun `saveMonthlySnapshot throws for unknown world`() {
        `when`(worldStateRepository.findById(1.toShort())).thenReturn(Optional.empty())

        assertThrows(IllegalArgumentException::class.java) {
            service.saveMonthlySnapshot(1L, 200, 6)
        }
    }

    @Test
    fun `saveMonthlySnapshot creates snapshot with cities and nations`() {
        val world = createWorld()
        `when`(worldStateRepository.findById(1.toShort())).thenReturn(Optional.of(world))
        `when`(cityRepository.findByWorldId(1L)).thenReturn(
            listOf(
                City(id = 1, worldId = 1, name = "낙양", level = 5, nationId = 1,
                    pop = 5000, popMax = 10000, agri = 3000, agriMax = 5000,
                    comm = 2000, commMax = 5000, secu = 1000, secuMax = 3000,
                    wall = 500, wallMax = 1000, def = 300, defMax = 500),
            )
        )
        `when`(nationRepository.findByWorldId(1L)).thenReturn(
            listOf(
                Nation(id = 1, worldId = 1, name = "위", color = "#FF0000", level = 7, capitalCityId = 1),
            )
        )
        `when`(generalRepository.findByWorldId(1L)).thenReturn(
            listOf(
                General(id = 1, worldId = 1, name = "조조", nationId = 1, cityId = 1,
                    leadership = 90, strength = 70, intel = 90, gold = 5000, rice = 5000,
                    experience = 1000, dedication = 500, npcState = 0,
                    turnTime = OffsetDateTime.now()),
            )
        )
        `when`(yearbookHistoryRepository.findByWorldIdAndYearAndMonth(1L, 200.toShort(), 6.toShort()))
            .thenReturn(null)
        `when`(yearbookHistoryRepository.save(anyNonNull())).thenAnswer { it.arguments[0] }

        val result = service.saveMonthlySnapshot(1L, 200, 6)

        assertNotNull(result)
        verify(yearbookHistoryRepository).save(anyNonNull())
    }

    @Test
    fun `saveMonthlySnapshot generates non-empty hash`() {
        val world = createWorld()
        `when`(worldStateRepository.findById(1.toShort())).thenReturn(Optional.of(world))
        `when`(cityRepository.findByWorldId(1L)).thenReturn(emptyList())
        `when`(nationRepository.findByWorldId(1L)).thenReturn(emptyList())
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())
        `when`(yearbookHistoryRepository.findByWorldIdAndYearAndMonth(1L, 200.toShort(), 6.toShort()))
            .thenReturn(null)
        `when`(yearbookHistoryRepository.save(anyNonNull())).thenAnswer { it.arguments[0] }

        val result = service.saveMonthlySnapshot(1L, 200, 6)

        assertNotNull(result.hash)
        assertTrue(result.hash.isNotBlank(), "Hash should not be blank")
        assertEquals(64, result.hash.length, "SHA-256 hex should be 64 chars")
    }

    @Test
    fun `saveMonthlySnapshot deterministic hash for same data`() {
        val world = createWorld()
        `when`(worldStateRepository.findById(1.toShort())).thenReturn(Optional.of(world))
        `when`(cityRepository.findByWorldId(1L)).thenReturn(emptyList())
        `when`(nationRepository.findByWorldId(1L)).thenReturn(emptyList())
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())
        `when`(yearbookHistoryRepository.findByWorldIdAndYearAndMonth(1L, 200.toShort(), 6.toShort()))
            .thenReturn(null)
        `when`(yearbookHistoryRepository.save(anyNonNull())).thenAnswer { it.arguments[0] }

        val result1 = service.saveMonthlySnapshot(1L, 200, 6)
        val result2 = service.saveMonthlySnapshot(1L, 200, 6)

        assertEquals(result1.hash, result2.hash, "Same data should produce same hash")
    }
}
