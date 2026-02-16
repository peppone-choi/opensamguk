package com.opensam.engine

import com.opensam.entity.Event
import com.opensam.entity.Message
import com.opensam.entity.WorldState
import com.opensam.repository.EventRepository
import com.opensam.repository.MessageRepository
import com.opensam.repository.NationRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.ArgumentCaptor

class EventServiceTest {

    private lateinit var service: EventService
    private lateinit var eventRepository: EventRepository
    private lateinit var nationRepository: NationRepository
    private lateinit var messageRepository: MessageRepository

    /** Mockito `any()` returns null which breaks Kotlin non-null params. This helper casts it. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = org.mockito.Mockito.any<T>() as T

    @BeforeEach
    fun setUp() {
        eventRepository = mock(EventRepository::class.java)
        nationRepository = mock(NationRepository::class.java)
        messageRepository = mock(MessageRepository::class.java)

        // Default: messageRepository.save returns the argument
        `when`(messageRepository.save(anyNonNull<Message>())).thenAnswer { it.arguments[0] }

        service = EventService(eventRepository, nationRepository, messageRepository)
    }

    private fun createWorld(year: Short = 200, month: Short = 3): WorldState {
        return WorldState(
            id = 1,
            scenarioCode = "test",
            currentYear = year,
            currentMonth = month,
            tickSeconds = 300,
        )
    }

    private fun createEvent(
        id: Long,
        targetCode: String,
        condition: MutableMap<String, Any>,
        action: MutableMap<String, Any>,
        priority: Short = 100,
    ): Event {
        return Event(
            id = id,
            worldId = 1,
            targetCode = targetCode,
            condition = condition,
            action = action,
            priority = priority,
        )
    }

    // ========== dispatchEvents: condition evaluation ==========

    @Test
    fun `dispatchEvents processes event when condition is always_true`() {
        val world = createWorld(year = 200, month = 3)
        val event = createEvent(
            id = 1,
            targetCode = "turn_start",
            condition = mutableMapOf("type" to "always_true"),
            action = mutableMapOf("type" to "log", "message" to "Test event"),
        )

        `when`(eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(1L, "turn_start"))
            .thenReturn(listOf(event))

        service.dispatchEvents(world, "turn_start")

        verify(messageRepository).save(any())
    }

    @Test
    fun `dispatchEvents skips event when condition is always_false`() {
        val world = createWorld(year = 200, month = 3)
        val event = createEvent(
            id = 1,
            targetCode = "turn_start",
            condition = mutableMapOf("type" to "always_false"),
            action = mutableMapOf("type" to "log", "message" to "Should not fire"),
        )

        `when`(eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(1L, "turn_start"))
            .thenReturn(listOf(event))

        service.dispatchEvents(world, "turn_start")

        verify(messageRepository, never()).save(any())
    }

    @Test
    fun `dispatchEvents matches date condition when year and month match`() {
        val world = createWorld(year = 200, month = 3)
        val event = createEvent(
            id = 1,
            targetCode = "turn_start",
            condition = mutableMapOf("type" to "date", "year" to 200, "month" to 3),
            action = mutableMapOf("type" to "log", "message" to "Date matched"),
        )

        `when`(eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(1L, "turn_start"))
            .thenReturn(listOf(event))

        service.dispatchEvents(world, "turn_start")

        verify(messageRepository).save(any())
    }

    @Test
    fun `dispatchEvents does not match date condition when year differs`() {
        val world = createWorld(year = 201, month = 3)
        val event = createEvent(
            id = 1,
            targetCode = "turn_start",
            condition = mutableMapOf("type" to "date", "year" to 200, "month" to 3),
            action = mutableMapOf("type" to "log", "message" to "Should not fire"),
        )

        `when`(eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(1L, "turn_start"))
            .thenReturn(listOf(event))

        service.dispatchEvents(world, "turn_start")

        verify(messageRepository, never()).save(any())
    }

    @Test
    fun `dispatchEvents matches date_after condition when date is after threshold`() {
        val world = createWorld(year = 201, month = 5)
        val event = createEvent(
            id = 1,
            targetCode = "turn_start",
            condition = mutableMapOf("type" to "date_after", "year" to 200, "month" to 12),
            action = mutableMapOf("type" to "log", "message" to "Date after matched"),
        )

        `when`(eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(1L, "turn_start"))
            .thenReturn(listOf(event))

        service.dispatchEvents(world, "turn_start")

        verify(messageRepository).save(any())
    }

    // ========== dispatchEvents: action execution ==========

    @Test
    fun `dispatchEvents executes log action and saves message`() {
        val world = createWorld(year = 200, month = 3)
        val event = createEvent(
            id = 1,
            targetCode = "turn_start",
            condition = mutableMapOf("type" to "always_true"),
            action = mutableMapOf("type" to "log", "message" to "History log test"),
        )

        `when`(eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(1L, "turn_start"))
            .thenReturn(listOf(event))

        service.dispatchEvents(world, "turn_start")

        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messageRepository).save(captor.capture())
        assertEquals("world_history", captor.value.mailboxCode)
        assertEquals("history", captor.value.messageType)
    }

    @Test
    fun `dispatchEvents executes notice action and saves notice message`() {
        val world = createWorld(year = 200, month = 3)
        val event = createEvent(
            id = 1,
            targetCode = "turn_start",
            condition = mutableMapOf("type" to "always_true"),
            action = mutableMapOf("type" to "notice", "message" to "Notice test"),
        )

        `when`(eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(1L, "turn_start"))
            .thenReturn(listOf(event))

        service.dispatchEvents(world, "turn_start")

        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messageRepository).save(captor.capture())
        assertEquals("notice", captor.value.mailboxCode)
        assertEquals("notice", captor.value.messageType)
    }

    @Test
    fun `dispatchEvents executes delete_event action`() {
        val world = createWorld(year = 200, month = 3)
        val event = createEvent(
            id = 1,
            targetCode = "turn_start",
            condition = mutableMapOf("type" to "always_true"),
            action = mutableMapOf("type" to "delete_event", "eventId" to 99),
        )

        `when`(eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(1L, "turn_start"))
            .thenReturn(listOf(event))

        service.dispatchEvents(world, "turn_start")

        verify(eventRepository).deleteById(99L)
    }

    // ========== dispatchEvents: multiple events and priority ==========

    @Test
    fun `dispatchEvents processes multiple events in priority order`() {
        val world = createWorld(year = 200, month = 3)
        val event1 = createEvent(
            id = 1,
            targetCode = "turn_start",
            condition = mutableMapOf("type" to "always_true"),
            action = mutableMapOf("type" to "log", "message" to "Event 1"),
            priority = 200.toShort(),
        )
        val event2 = createEvent(
            id = 2,
            targetCode = "turn_start",
            condition = mutableMapOf("type" to "always_true"),
            action = mutableMapOf("type" to "log", "message" to "Event 2"),
            priority = 100,
        )

        `when`(eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(1L, "turn_start"))
            .thenReturn(listOf(event1, event2))

        service.dispatchEvents(world, "turn_start")

        verify(messageRepository, times(2)).save(any())
    }

    // ========== dispatchEvents: empty events ==========

    @Test
    fun `dispatchEvents handles no events gracefully`() {
        val world = createWorld()

        `when`(eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(1L, "turn_start"))
            .thenReturn(emptyList())

        assertDoesNotThrow {
            service.dispatchEvents(world, "turn_start")
        }

        verify(messageRepository, never()).save(any())
    }

    // ========== dispatchEvents: remain_nation condition ==========

    @Test
    fun `dispatchEvents matches remain_nation when nation count is below threshold`() {
        val world = createWorld(year = 200, month = 3)
        val event = createEvent(
            id = 1,
            targetCode = "turn_start",
            condition = mutableMapOf("type" to "remain_nation", "count" to 5),
            action = mutableMapOf("type" to "log", "message" to "Few nations remain"),
        )

        `when`(eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(1L, "turn_start"))
            .thenReturn(listOf(event))
        `when`(nationRepository.findByWorldId(1L)).thenReturn(emptyList())

        service.dispatchEvents(world, "turn_start")

        verify(messageRepository).save(any())
    }
}
