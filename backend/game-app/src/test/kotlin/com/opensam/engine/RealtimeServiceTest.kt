package com.opensam.engine

import com.opensam.command.CommandExecutor
import com.opensam.command.CommandRegistry
import com.opensam.engine.modifier.ModifierService
import com.opensam.entity.General
import com.opensam.entity.WorldState
import com.opensam.repository.*
import com.opensam.service.GameEventService
import com.opensam.service.ScenarioService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.OffsetDateTime
import java.util.*

class RealtimeServiceTest {

    private lateinit var service: RealtimeService
    private lateinit var generalRepository: GeneralRepository
    private lateinit var generalTurnRepository: GeneralTurnRepository
    private lateinit var cityRepository: CityRepository
    private lateinit var nationRepository: NationRepository
    private lateinit var worldStateRepository: WorldStateRepository
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var commandRegistry: CommandRegistry
    private lateinit var gameEventService: GameEventService
    private lateinit var scenarioService: ScenarioService
    private lateinit var modifierService: ModifierService

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = any<T>() as T

    @BeforeEach
    fun setUp() {
        generalRepository = mock(GeneralRepository::class.java)
        generalTurnRepository = mock(GeneralTurnRepository::class.java)
        cityRepository = mock(CityRepository::class.java)
        nationRepository = mock(NationRepository::class.java)
        worldStateRepository = mock(WorldStateRepository::class.java)
        commandExecutor = mock(CommandExecutor::class.java)
        commandRegistry = mock(CommandRegistry::class.java)
        gameEventService = mock(GameEventService::class.java)
        scenarioService = mock(ScenarioService::class.java)
        modifierService = mock(ModifierService::class.java)

        service = RealtimeService(
            generalRepository,
            generalTurnRepository,
            cityRepository,
            nationRepository,
            worldStateRepository,
            commandExecutor,
            commandRegistry,
            gameEventService,
            scenarioService,
            modifierService,
        )
    }

    private fun createGeneral(
        id: Long = 1,
        worldId: Long = 1,
        commandEndTime: OffsetDateTime? = null,
        commandPoints: Int = 10,
        officerLevel: Short = 1,
    ): General {
        return General(
            id = id,
            worldId = worldId,
            name = "테스트",
            nationId = 1,
            cityId = 1,
            commandEndTime = commandEndTime,
            commandPoints = commandPoints,
            officerLevel = officerLevel,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createWorld(realtimeMode: Boolean = true): WorldState {
        return WorldState(
            id = 1,
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 6,
            tickSeconds = 300,
            realtimeMode = realtimeMode,
            commandPointRegenRate = 1,
        )
    }

    @Test
    fun `submitCommand fails when world is not in realtime mode`() {
        val general = createGeneral()
        val world = createWorld(realtimeMode = false)

        `when`(generalRepository.findById(1L)).thenReturn(Optional.of(general))
        `when`(worldStateRepository.findById(1.toShort())).thenReturn(Optional.of(world))

        val result = service.submitCommand(1L, "징병", null)

        assertFalse(result.success)
        assertTrue(result.logs.any { it.contains("realtime") })
    }

    @Test
    fun `submitCommand fails when command already in progress`() {
        val general = createGeneral(commandEndTime = OffsetDateTime.now().plusMinutes(5))
        val world = createWorld()

        `when`(generalRepository.findById(1L)).thenReturn(Optional.of(general))
        `when`(worldStateRepository.findById(1.toShort())).thenReturn(Optional.of(world))

        val result = service.submitCommand(1L, "징병", null)

        assertFalse(result.success)
        assertTrue(result.logs.any { it.contains("in progress") })
    }

    @Test
    fun `submitNationCommand fails when officer level too low`() {
        val general = createGeneral(officerLevel = 3)

        `when`(generalRepository.findById(1L)).thenReturn(Optional.of(general))

        val result = service.submitNationCommand(1L, "천도", null)

        assertFalse(result.success)
        assertTrue(result.logs.any { it.contains("권한") })
    }

    @Test
    fun `regenerateCommandPoints increases command points up to cap`() {
        val world = createWorld()
        world.commandPointRegenRate = 5
        val general = createGeneral(commandPoints = 97)

        `when`(generalRepository.findByWorldId(1L)).thenReturn(listOf(general))

        service.regenerateCommandPoints(world)

        assertEquals(100, general.commandPoints, "Should cap at 100")
        verify(generalRepository).save(general)
    }

    @Test
    fun `getRealtimeStatus returns null for unknown general`() {
        `when`(generalRepository.findById(999L)).thenReturn(Optional.empty())

        val result = service.getRealtimeStatus(999L)

        assertNull(result)
    }
}
