package com.opensam.engine

import com.opensam.entity.WorldState
import com.opensam.repository.WorldStateRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.OffsetDateTime

class TurnDaemonTest {

    private lateinit var daemon: TurnDaemon
    private lateinit var turnService: TurnService
    private lateinit var realtimeService: RealtimeService
    private lateinit var worldStateRepository: WorldStateRepository

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = any<T>() as T

    @BeforeEach
    fun setUp() {
        turnService = mock(TurnService::class.java)
        realtimeService = mock(RealtimeService::class.java)
        worldStateRepository = mock(WorldStateRepository::class.java)

        daemon = TurnDaemon(
            turnService,
            realtimeService,
            "test-sha",
            worldStateRepository,
        )
    }

    private fun createWorld(id: Short = 1, realtimeMode: Boolean = false, gatewayActive: Any? = null): WorldState {
        val meta = if (gatewayActive != null) mutableMapOf<String, Any>("gatewayActive" to gatewayActive)
        else mutableMapOf()
        return WorldState(
            id = id,
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 6,
            tickSeconds = 300,
            updatedAt = OffsetDateTime.now(),
            realtimeMode = realtimeMode,
            meta = meta,
        )
    }

    @Test
    fun `tick calls turnService for non-realtime worlds`() {
        val world = createWorld(realtimeMode = false)
        `when`(worldStateRepository.findByCommitSha("test-sha")).thenReturn(listOf(world))

        daemon.tick()

        verify(turnService).processWorld(world)
        verify(realtimeService, never()).processCompletedCommands(anyNonNull())
    }

    @Test
    fun `tick calls realtimeService for realtime worlds`() {
        val world = createWorld(realtimeMode = true)
        `when`(worldStateRepository.findByCommitSha("test-sha")).thenReturn(listOf(world))

        daemon.tick()

        verify(realtimeService).processCompletedCommands(world)
        verify(realtimeService).regenerateCommandPoints(world)
        verify(turnService, never()).processWorld(anyNonNull())
    }

    @Test
    fun `tick skips worlds with gatewayActive false`() {
        val world = createWorld(gatewayActive = false)
        `when`(worldStateRepository.findByCommitSha("test-sha")).thenReturn(listOf(world))

        daemon.tick()

        verify(turnService, never()).processWorld(anyNonNull())
    }

    @Test
    fun `pause prevents tick from running`() {
        val world = createWorld()
        `when`(worldStateRepository.findByCommitSha("test-sha")).thenReturn(listOf(world))

        daemon.pause()
        daemon.tick()

        verify(turnService, never()).processWorld(anyNonNull())
        assertEquals(TurnDaemon.DaemonState.PAUSED, daemon.getStatus())
    }

    @Test
    fun `resume allows tick to run again after pause`() {
        val world = createWorld()
        `when`(worldStateRepository.findByCommitSha("test-sha")).thenReturn(listOf(world))

        daemon.pause()
        daemon.resume()
        daemon.tick()

        verify(turnService).processWorld(world)
    }
}
