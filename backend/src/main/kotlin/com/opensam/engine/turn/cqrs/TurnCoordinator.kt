package com.opensam.engine.turn.cqrs

import com.opensam.engine.turn.cqrs.memory.DirtyTracker
import com.opensam.engine.turn.cqrs.memory.InMemoryTurnProcessor
import com.opensam.engine.turn.cqrs.memory.WorldStateLoader
import com.opensam.engine.turn.cqrs.persist.WorldStatePersister
import com.opensam.entity.WorldState
import com.opensam.repository.WorldStateRepository
import com.opensam.service.GameEventService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class TurnCoordinator(
    private val worldStateLoader: WorldStateLoader,
    private val inMemoryTurnProcessor: InMemoryTurnProcessor,
    private val worldStatePersister: WorldStatePersister,
    private val worldStateRepository: WorldStateRepository,
    private val turnStatusService: TurnStatusService,
    private val gameEventService: GameEventService,
) {
    private val logger = LoggerFactory.getLogger(TurnCoordinator::class.java)

    fun processWorld(world: WorldState) {
        val worldId = world.id.toLong()
        try {
            transition(worldId, TurnLifecycleState.LOADING)
            val state = worldStateLoader.loadWorldState(worldId)
            val dirtyTracker = DirtyTracker()

            transition(worldId, TurnLifecycleState.PROCESSING)
            val result = inMemoryTurnProcessor.process(state, dirtyTracker, world)

            transition(worldId, TurnLifecycleState.PERSISTING)
            worldStatePersister.persist(state, dirtyTracker, state.worldId)
            worldStateRepository.save(world)

            transition(worldId, TurnLifecycleState.PUBLISHING)
            publish(worldId, result)
        } catch (e: Exception) {
            transition(worldId, TurnLifecycleState.FAILED)
            logger.error("Turn processing failed for world {}: {}", worldId, e.message, e)
        } finally {
            transition(worldId, TurnLifecycleState.IDLE)
        }
    }

    private fun publish(worldId: Long, result: TurnResult) {
        if (result.events.isEmpty()) return

        result.events.forEach { event ->
            if (event.type == com.opensam.engine.turn.cqrs.memory.InMemoryTurnProcessor.EVENT_TURN_ADVANCED) {
                val year = (event.payload["year"] as? Int) ?: return@forEach
                val month = (event.payload["month"] as? Int) ?: return@forEach
                gameEventService.broadcastTurnAdvance(worldId, year, month)
            }
        }
    }

    private fun transition(worldId: Long, state: TurnLifecycleState) {
        turnStatusService.updateStatus(worldId, state)
    }
}
