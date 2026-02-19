package com.opensam.engine.turn.cqrs

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@Service
class TurnStatusService {
    private val statuses = ConcurrentHashMap<Long, AtomicReference<TurnLifecycleState>>()

    fun getStatus(worldId: Long): TurnLifecycleState {
        return statuses[worldId]?.get() ?: TurnLifecycleState.IDLE
    }

    fun updateStatus(worldId: Long, state: TurnLifecycleState) {
        statuses.computeIfAbsent(worldId) { AtomicReference(TurnLifecycleState.IDLE) }
            .set(state)
    }
}
