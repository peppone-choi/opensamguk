package com.opensam.engine

import com.opensam.command.CommandExecutor
import com.opensam.command.CommandRegistry
import com.opensam.engine.ai.GeneralAI
import com.opensam.engine.ai.NationAI
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.repository.*
import com.opensam.service.InheritanceService
import com.opensam.service.ScenarioService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.OffsetDateTime

class TurnServiceTest {

    private lateinit var service: TurnService
    private lateinit var worldStateRepository: WorldStateRepository
    private lateinit var generalRepository: GeneralRepository
    private lateinit var generalTurnRepository: GeneralTurnRepository
    private lateinit var nationTurnRepository: NationTurnRepository
    private lateinit var cityRepository: CityRepository
    private lateinit var nationRepository: NationRepository
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var commandRegistry: CommandRegistry
    private lateinit var scenarioService: ScenarioService
    private lateinit var economyService: EconomyService
    private lateinit var eventService: EventService
    private lateinit var diplomacyService: DiplomacyService
    private lateinit var generalMaintenanceService: GeneralMaintenanceService
    private lateinit var specialAssignmentService: SpecialAssignmentService
    private lateinit var npcSpawnService: NpcSpawnService
    private lateinit var inheritanceService: InheritanceService
    private lateinit var generalAI: GeneralAI
    private lateinit var nationAI: NationAI

    /** Mockito `any()` returns null which breaks Kotlin non-null params. This helper casts it. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = any<T>() as T

    @BeforeEach
    fun setUp() {
        worldStateRepository = mock(WorldStateRepository::class.java)
        generalRepository = mock(GeneralRepository::class.java)
        generalTurnRepository = mock(GeneralTurnRepository::class.java)
        nationTurnRepository = mock(NationTurnRepository::class.java)
        cityRepository = mock(CityRepository::class.java)
        nationRepository = mock(NationRepository::class.java)
        commandExecutor = mock(CommandExecutor::class.java)
        commandRegistry = mock(CommandRegistry::class.java)
        scenarioService = mock(ScenarioService::class.java)
        economyService = mock(EconomyService::class.java)
        eventService = mock(EventService::class.java)
        diplomacyService = mock(DiplomacyService::class.java)
        generalMaintenanceService = mock(GeneralMaintenanceService::class.java)
        specialAssignmentService = mock(SpecialAssignmentService::class.java)
        npcSpawnService = mock(NpcSpawnService::class.java)
        inheritanceService = mock(InheritanceService::class.java)
        generalAI = mock(GeneralAI::class.java)
        nationAI = mock(NationAI::class.java)

        service = TurnService(
            worldStateRepository,
            generalRepository,
            generalTurnRepository,
            nationTurnRepository,
            cityRepository,
            nationRepository,
            commandExecutor,
            commandRegistry,
            scenarioService,
            economyService,
            eventService,
            diplomacyService,
            generalMaintenanceService,
            specialAssignmentService,
            npcSpawnService,
            inheritanceService,
            generalAI,
            nationAI,
        )

        // Default: worldStateRepository.save returns the argument
        `when`(worldStateRepository.save(anyNonNull<WorldState>())).thenAnswer { it.arguments[0] }
    }

    private fun createWorld(
        year: Short = 200,
        month: Short = 6,
        tickSeconds: Int = 300,
        updatedAt: OffsetDateTime = OffsetDateTime.now().minusSeconds(600),
    ): WorldState {
        return WorldState(
            id = 1,
            scenarioCode = "test",
            currentYear = year,
            currentMonth = month,
            tickSeconds = tickSeconds,
            updatedAt = updatedAt,
        )
    }

    // ========== advanceMonth (tested indirectly through processWorld) ==========

    @Test
    fun `processWorld advances month by 1 when tick elapsed`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = now.minusSeconds(400))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

        service.processWorld(world)

        assertEquals(7.toShort(), world.currentMonth, "Month should advance from 6 to 7")
        assertEquals(200.toShort(), world.currentYear, "Year should stay 200")
    }

    @Test
    fun `processWorld advances year when month goes past 12`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 12, tickSeconds = 300, updatedAt = now.minusSeconds(400))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

        service.processWorld(world)

        assertEquals(1.toShort(), world.currentMonth, "Month should wrap to 1")
        assertEquals(201.toShort(), world.currentYear, "Year should advance to 201")
    }

    @Test
    fun `processWorld does not advance when tick not yet elapsed`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = now.plusSeconds(100))

        service.processWorld(world)

        assertEquals(6.toShort(), world.currentMonth, "Month should not change")
        assertEquals(200.toShort(), world.currentYear, "Year should not change")
    }

    @Test
    fun `processWorld advances multiple months when multiple ticks elapsed`() {
        val now = OffsetDateTime.now()
        // 2 ticks worth of time elapsed (700s > 2 * 300s)
        val world = createWorld(year = 200, month = 3, tickSeconds = 300, updatedAt = now.minusSeconds(700))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

        service.processWorld(world)

        assertEquals(5.toShort(), world.currentMonth, "Month should advance by 2")
    }

    // ========== processWorld calls services ==========

    @Test
    fun `processWorld calls economy and diplomacy services`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = now.minusSeconds(400))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

        service.processWorld(world)

        verify(economyService).processMonthly(anyNonNull())
        verify(economyService).processDisasterOrBoom(anyNonNull())
        verify(economyService).randomizeCityTradeRate(anyNonNull())
        verify(diplomacyService).processDiplomacyTurn(anyNonNull())
    }

    @Test
    fun `processWorld calls generalMaintenance`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = now.minusSeconds(400))
        val generals = listOf(
            General(id = 1, worldId = 1, name = "테스트", nationId = 1, cityId = 1, turnTime = OffsetDateTime.now())
        )

        `when`(generalRepository.findByWorldId(1L)).thenReturn(generals)
        `when`(generalTurnRepository.findByGeneralIdOrderByTurnIdx(1L)).thenReturn(emptyList())
        `when`(cityRepository.findById(1L)).thenReturn(java.util.Optional.empty())

        service.processWorld(world)

        verify(generalMaintenanceService).processGeneralMaintenance(anyNonNull(), anyList())
    }

    // ========== processWorld: service failure resilience ==========

    @Test
    fun `processWorld continues when economyService throws`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = now.minusSeconds(400))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())
        doThrow(RuntimeException("DB error")).`when`(economyService).processMonthly(anyNonNull())

        // Should not throw - continues processing
        assertDoesNotThrow { service.processWorld(world) }

        // Should still advance month
        assertEquals(7.toShort(), world.currentMonth)
    }

    @Test
    fun `processWorld continues when eventService throws`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = now.minusSeconds(400))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())
        doThrow(RuntimeException("Event error")).`when`(eventService).dispatchEvents(anyNonNull(), anyString())

        assertDoesNotThrow { service.processWorld(world) }
        assertEquals(7.toShort(), world.currentMonth)
    }

    // ========== processWorld: saves world at end ==========

    @Test
    fun `processWorld saves world state`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = now.minusSeconds(400))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

        service.processWorld(world)

        verify(worldStateRepository).save(world)
    }

    // ========== processWorld: updatedAt progression ==========

    @Test
    fun `processWorld updates world updatedAt by tickDuration`() {
        val now = OffsetDateTime.now()
        val startTime = now.minusSeconds(400)
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = startTime)
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

        service.processWorld(world)

        // updatedAt should be startTime + 300s
        val expected = startTime.plusSeconds(300)
        assertEquals(expected, world.updatedAt, "updatedAt should advance by tick duration")
    }

    // ========== updateTraffic (supply state recalc) ==========

    @Test
    fun `processWorld calls updateCitySupplyState each turn`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = now.minusSeconds(400))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

        service.processWorld(world)

        verify(economyService).updateCitySupplyState(anyNonNull())
    }

    @Test
    fun `processWorld calls updateCitySupplyState for each catch-up turn`() {
        val now = OffsetDateTime.now()
        // 2 ticks elapsed
        val world = createWorld(year = 200, month = 3, tickSeconds = 300, updatedAt = now.minusSeconds(700))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

        service.processWorld(world)

        verify(economyService, times(2)).updateCitySupplyState(anyNonNull())
    }

    // ========== strategic command limit reset ==========

    @Test
    fun `processWorld decrements strategic command limits`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = now.minusSeconds(400))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

        val nation = Nation(
            id = 1, worldId = 1, name = "위", color = "#FF0000",
            strategicCmdLimit = 5,
        )
        `when`(nationRepository.findByWorldId(1L)).thenReturn(listOf(nation))

        service.processWorld(world)

        assertEquals(4.toShort(), nation.strategicCmdLimit, "Strategic limit should decrement by 1")
    }

    @Test
    fun `processWorld does not decrement strategic limit below zero`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = now.minusSeconds(400))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

        val nation = Nation(
            id = 1, worldId = 1, name = "위", color = "#FF0000",
            strategicCmdLimit = 0,
        )
        `when`(nationRepository.findByWorldId(1L)).thenReturn(listOf(nation))

        service.processWorld(world)

        assertEquals(0.toShort(), nation.strategicCmdLimit, "Strategic limit should stay at 0")
    }

    @Test
    fun `processWorld decrements strategic limit for each catch-up turn`() {
        val now = OffsetDateTime.now()
        // 2 ticks elapsed
        val world = createWorld(year = 200, month = 3, tickSeconds = 300, updatedAt = now.minusSeconds(700))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())

        val nation = Nation(
            id = 1, worldId = 1, name = "위", color = "#FF0000",
            strategicCmdLimit = 10,
        )
        `when`(nationRepository.findByWorldId(1L)).thenReturn(listOf(nation))

        service.processWorld(world)

        assertEquals(8.toShort(), nation.strategicCmdLimit, "Strategic limit should decrement by 2 for 2 turns")
    }

    // ========== catch-up resilience ==========

    @Test
    fun `processWorld continues when updateTraffic throws`() {
        val now = OffsetDateTime.now()
        val world = createWorld(year = 200, month = 6, tickSeconds = 300, updatedAt = now.minusSeconds(400))
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())
        doThrow(RuntimeException("Traffic error")).`when`(economyService).updateCitySupplyState(anyNonNull())

        assertDoesNotThrow { service.processWorld(world) }
        assertEquals(7.toShort(), world.currentMonth, "Month should still advance")
    }
}
