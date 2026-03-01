package com.opensam.engine.turn.cqrs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TurnStatusServiceTest {

    private lateinit var service: TurnStatusService

    @BeforeEach
    fun setUp() {
        service = TurnStatusService()
    }

    @Test
    fun `getStatus returns IDLE for unknown worldId`() {
        val status = service.getStatus(999L)
        assertEquals(TurnLifecycleState.IDLE, status)
    }

    @Test
    fun `updateStatus persists state and getStatus returns it`() {
        service.updateStatus(1L, TurnLifecycleState.PROCESSING)

        assertEquals(TurnLifecycleState.PROCESSING, service.getStatus(1L))
    }

    @Test
    fun `updateStatus overwrites previous state`() {
        service.updateStatus(1L, TurnLifecycleState.LOADING)
        service.updateStatus(1L, TurnLifecycleState.PERSISTING)

        assertEquals(TurnLifecycleState.PERSISTING, service.getStatus(1L))
    }

    @Test
    fun `multiple worlds have independent status`() {
        service.updateStatus(1L, TurnLifecycleState.PROCESSING)
        service.updateStatus(2L, TurnLifecycleState.FAILED)
        service.updateStatus(3L, TurnLifecycleState.PUBLISHING)

        assertEquals(TurnLifecycleState.PROCESSING, service.getStatus(1L))
        assertEquals(TurnLifecycleState.FAILED, service.getStatus(2L))
        assertEquals(TurnLifecycleState.PUBLISHING, service.getStatus(3L))
        assertEquals(TurnLifecycleState.IDLE, service.getStatus(4L))
    }

    @Test
    fun `updateStatus back to IDLE works`() {
        service.updateStatus(1L, TurnLifecycleState.PROCESSING)
        service.updateStatus(1L, TurnLifecycleState.IDLE)

        assertEquals(TurnLifecycleState.IDLE, service.getStatus(1L))
    }
}
