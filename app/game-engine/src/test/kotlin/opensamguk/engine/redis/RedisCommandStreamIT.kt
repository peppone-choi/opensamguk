package opensamguk.engine.redis

import opensamguk.common.world.WorldId
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonStreamKeys
import opensamguk.common.wire.WIRE_PAYLOAD_FIELD
import opensamguk.common.wire.WireJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.stream.ObjectRecord
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
@Testcontainers(disabledWithoutDocker = true)
class RedisCommandStreamIT {
    private val profile = "che:scenario_2"
    private val keys = TurnDaemonStreamKeys.of(profile, WorldId(1))
    private fun addCommand(template: StringRedisTemplate, requestId: String, generalId: Int) {
        val envelope = TurnDaemonCommandEnvelope(
            requestId = requestId,
            sentAt = "0200-01-01T00:00:00.000Z",
            command = TurnDaemonCommand.TroopJoin(requestId = requestId, generalId = generalId, troopId = 7),
        )
        val payload = WireJson.encodeToString(TurnDaemonCommandEnvelope.serializer(), envelope)
        val record: ObjectRecord<String, Map<String, String>> = StreamRecords
            .newRecord()
            .ofObject(mapOf(WIRE_PAYLOAD_FIELD to payload))
            .withStreamKey(keys.commandStream)
        @Suppress("UNCHECKED_CAST")
        template.opsForStream<Any, Any>().add(record as ObjectRecord<String, Any>)
    }
    @AfterTest
    fun cleanup() {
        template.delete(keys.commandStream)
    }
    @Test
    fun `only-new cursor ignores pre-construction message and drains the post-construction one`() {
        assertTrue(redis.isRunning, "redis:7-alpine container must be running")
        // message BEFORE consumer construction -> startId '$' resolves past it -> ignored
        addCommand(template, "before", 100)
        val consumer = RedisCommandStream(template, profile, WorldId(1)) // startId defaults to '$'
        val startCursor = consumer.lastId()
        // a NEW message after construction (the resume the consumer should see)
        addCommand(template, "after", 200)
        val drained = consumer.readCommands(2000)
        assertEquals(1, drained.size, "exactly the post-construction message is consumed")
        val cmd = drained.single()
        assertTrue(cmd is TurnDaemonCommand.TroopJoin)
        assertEquals(200, (cmd as TurnDaemonCommand.TroopJoin).generalId)
        assertNotEquals(startCursor, consumer.lastId(), "lastId advanced past the consumed record")
        val second = consumer.readCommands(500)
        assertTrue(second.isEmpty(), "second drain is empty")
    }

    @Test
    fun `consumer group wake requires explicit ack after read`() {
        assertTrue(redis.isRunning, "redis:7-alpine container must be running")
        val consumer = RedisCommandStream(template, profile, WorldId(1))
        addCommand(template, "ack-me", 300)

        val first = consumer.readWakeEnvelopes(2000)
        assertEquals(listOf("ack-me"), first.map { it.envelope.requestId })

        val pending = consumer.readWakeEnvelopes(500)
        assertEquals(listOf("ack-me"), pending.map { it.envelope.requestId }, "unacked wake remains pending")
        assertEquals(1L, consumer.acknowledgeWake(first.map { it.messageId }))
        val afterAck = consumer.readWakeEnvelopes(500)
        assertTrue(afterAck.isEmpty(), "acked wake is no longer replayed from the consumer PEL")
    }

    @Test
    fun `current consumer claims stale wake from another consumer PEL`() {
        assertTrue(redis.isRunning, "redis:7-alpine container must be running")
        val oldConsumer = RedisCommandStream(
            template,
            profile,
            WorldId(1),
            consumerName = "old-world-1",
            pendingClaimIdle = java.time.Duration.ZERO,
        )
        val currentConsumer = RedisCommandStream(
            template,
            profile,
            WorldId(1),
            consumerName = "world-1",
            pendingClaimIdle = java.time.Duration.ZERO,
        )
        addCommand(template, "takeover", 400)

        val claimedByOld = oldConsumer.readWakeEnvelopes(2000)
        assertEquals(listOf("takeover"), claimedByOld.map { it.envelope.requestId })

        val reclaimed = currentConsumer.readWakeEnvelopes(500)
        assertEquals(listOf("takeover"), reclaimed.map { it.envelope.requestId })
        assertEquals(1L, currentConsumer.acknowledgeWake(reclaimed.map { it.messageId }))
        assertTrue(currentConsumer.readWakeEnvelopes(500).isEmpty())
    }
    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)
        private lateinit var connectionFactory: LettuceConnectionFactory
        lateinit var template: StringRedisTemplate
        @BeforeAll
        @JvmStatic
        fun setup() {
            val config = RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379))
            connectionFactory = LettuceConnectionFactory(config)
            connectionFactory.afterPropertiesSet()
            template = StringRedisTemplate(connectionFactory)
            template.afterPropertiesSet()
        }
        @AfterAll
        @JvmStatic
        fun teardown() {
            connectionFactory.destroy()
        }
    }
}
