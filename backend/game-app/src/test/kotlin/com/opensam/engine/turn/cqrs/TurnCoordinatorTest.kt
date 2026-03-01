package com.opensam.engine.turn.cqrs

import com.opensam.engine.turn.cqrs.memory.DirtyTracker
import com.opensam.engine.turn.cqrs.memory.InMemoryTurnProcessor
import com.opensam.engine.turn.cqrs.memory.InMemoryWorldState
import com.opensam.engine.turn.cqrs.memory.WorldStateLoader
import com.opensam.engine.turn.cqrs.persist.WorldStatePersister
import com.opensam.entity.WorldState
import com.opensam.repository.WorldStateRepository
import com.opensam.service.GameEventService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.OffsetDateTime

class TurnCoordinatorTest {

    private lateinit var coordinator: TurnCoordinator
    private lateinit var worldStateLoader: WorldStateLoader
    private lateinit var inMemoryTurnProcessor: InMemoryTurnProcessor
    private lateinit var worldStatePersister: WorldStatePersister
    private lateinit var worldStateRepository: WorldStateRepository
    private lateinit var turnStatusService: TurnStatusService
    private lateinit var gameEventService: GameEventService

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = any<T>() as T

    @BeforeEach
    fun setUp() {
        worldStateLoader = mock(WorldStateLoader::class.java)
        inMemoryTurnProcessor = mock(InMemoryTurnProcessor::class.java)
        worldStatePersister = mock(WorldStatePersister::class.java)
        worldStateRepository = mock(WorldStateRepository::class.java)
        turnStatusService = mock(TurnStatusService::class.java)
        gameEventService = mock(GameEventService::class.java)

        coordinator = TurnCoordinator(
            worldStateLoader,
            inMemoryTurnProcessor,
            worldStatePersister,
            worldStateRepository,
            turnStatusService,
            gameEventService,
        )
    }

    private fun createWorld(): WorldState {
        return WorldState(
            id = 1,
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 6,
            tickSeconds = 300,
            updatedAt = OffsetDateTime.now().minusSeconds(400),
        )
    }

    @Test
    fun `processWorld transitions through lifecycle states`() {
        val world = createWorld()
        val state = InMemoryWorldState(worldId = 1L)
        val result = TurnResult(advancedTurns = 1, events = emptyList())

        doReturn(state).`when`(worldStateLoader).loadWorldState(1L)
        doReturn(result).`when`(inMemoryTurnProcessor).process(anyNonNull(), anyNonNull(), anyNonNull())
        doAnswer { it.arguments[0] }.`when`(worldStateRepository).save(anyNonNull<WorldState>())

        coordinator.processWorld(world)

        val inOrder = inOrder(turnStatusService)
        inOrder.verify(turnStatusService).updateStatus(1L, TurnLifecycleState.LOADING)
        inOrder.verify(turnStatusService).updateStatus(1L, TurnLifecycleState.PROCESSING)
        inOrder.verify(turnStatusService).updateStatus(1L, TurnLifecycleState.PERSISTING)
        inOrder.verify(turnStatusService).updateStatus(1L, TurnLifecycleState.PUBLISHING)
        inOrder.verify(turnStatusService).updateStatus(1L, TurnLifecycleState.IDLE)
    }

    @Test
    fun `processWorld saves world state after persisting`() {
        val world = createWorld()
        val state = InMemoryWorldState(worldId = 1L)
        val result = TurnResult(advancedTurns = 1, events = emptyList())

        doReturn(state).`when`(worldStateLoader).loadWorldState(1L)
        doReturn(result).`when`(inMemoryTurnProcessor).process(anyNonNull(), anyNonNull(), anyNonNull())
        doAnswer { it.arguments[0] }.`when`(worldStateRepository).save(anyNonNull<WorldState>())

        coordinator.processWorld(world)

        verify(worldStateRepository).save(world)
    }

    @Test
    fun `processWorld transitions to FAILED then IDLE on exception`() {
        val world = createWorld()

        `when`(worldStateLoader.loadWorldState(1L)).thenThrow(RuntimeException("DB down"))

        coordinator.processWorld(world)

        verify(turnStatusService).updateStatus(1L, TurnLifecycleState.FAILED)
        verify(turnStatusService).updateStatus(1L, TurnLifecycleState.IDLE)
    }

    @Test
    fun `processWorld reaches publishing phase on successful turn`() {
        val world = createWorld()
        val state = InMemoryWorldState(worldId = 1L)
        val result = TurnResult(advancedTurns = 1, events = emptyList())

        doReturn(state).`when`(worldStateLoader).loadWorldState(1L)
        doReturn(result).`when`(inMemoryTurnProcessor).process(anyNonNull(), anyNonNull(), anyNonNull())
        doAnswer { it.arguments[0] }.`when`(worldStateRepository).save(anyNonNull<WorldState>())

        coordinator.processWorld(world)

        verify(turnStatusService).updateStatus(1L, TurnLifecycleState.PUBLISHING)
    }

    @Test
    fun `processWorld does not broadcast when no events`() {
        val world = createWorld()
        val state = InMemoryWorldState(worldId = 1L)
        val result = TurnResult(advancedTurns = 0, events = emptyList())

        doReturn(state).`when`(worldStateLoader).loadWorldState(1L)
        doReturn(result).`when`(inMemoryTurnProcessor).process(anyNonNull(), anyNonNull(), anyNonNull())
        doAnswer { it.arguments[0] }.`when`(worldStateRepository).save(anyNonNull<WorldState>())

        coordinator.processWorld(world)

        verify(gameEventService, never()).broadcastTurnAdvance(anyLong(), anyInt(), anyInt())
    }
}
