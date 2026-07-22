package opensamguk.engine.redis

import opensamguk.common.world.WorldId
import opensamguk.common.wire.commandResultKey
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.PendingCommandOutboxEvent
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandOutboxRelayTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-22T01:02:03Z"), ZoneOffset.UTC)

    @Test
    fun `publishPending publishes Redis payload then marks outbox published`() {
        val repository = mock(CommandResultRepository::class.java)
        val template = mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(template.opsForValue()).thenReturn(valueOps)
        val publisher = RealtimePublisher(template, "che:scenario_2", WorldId(1))
        `when`(repository.findPendingCommandResultOutbox(WorldId(1), 100)).thenReturn(
            listOf(PendingCommandOutboxEvent("evt-1", "req-1", """{"ok":true}""")),
        )
        `when`(repository.markCommandOutboxPublished(WorldId(1), "evt-1", clock.instant())).thenReturn(true)

        val relayed = CommandOutboxRelay(repository, publisher, WorldId(1), clock).publishPending()

        assertEquals(1, relayed)
        verify(valueOps).set(
            eq(commandResultKey("che:scenario_2", WorldId(1), "req-1")),
            eq("""{"ok":true}"""),
            eq(RealtimePublisher.COMMAND_RESULT_TTL),
        )
        verify(repository).markCommandOutboxPublished(WorldId(1), "evt-1", clock.instant())
    }

    @Test
    fun `publishPending leaves outbox unmarked when Redis publish fails`() {
        val repository = mock(CommandResultRepository::class.java)
        val template = mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(template.opsForValue()).thenReturn(valueOps)
        val publisher = RealtimePublisher(template, "che:scenario_2", WorldId(1))
        `when`(repository.findPendingCommandResultOutbox(WorldId(1), 100)).thenReturn(
            listOf(PendingCommandOutboxEvent("evt-1", "req-1", """{"ok":true}""")),
        )
        doThrow(IllegalStateException("redis down"))
            .`when`(valueOps)
            .set(
                eq(commandResultKey("che:scenario_2", WorldId(1), "req-1")),
                eq("""{"ok":true}"""),
                eq(RealtimePublisher.COMMAND_RESULT_TTL),
            )

        val relayed = CommandOutboxRelay(repository, publisher, WorldId(1), clock).publishPending()

        assertEquals(0, relayed)
        verify(repository, never()).markCommandOutboxPublished(WorldId(1), "evt-1", clock.instant())
    }
}
