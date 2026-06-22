package opensamguk.engine.redis
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
    private val keys = TurnDaemonStreamKeys.of(profile)
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
        val consumer = RedisCommandStream(template, profile) // startId defaults to '$'
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
