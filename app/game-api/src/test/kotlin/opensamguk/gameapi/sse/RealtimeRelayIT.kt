package opensamguk.gameapi.sse

import opensamguk.common.wire.RealtimeEvent
import opensamguk.common.wire.WireJson
import opensamguk.common.wire.gameEventChannel
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the pub/sub -> MessageListener delivery path that the production
 * [RealtimeSubscriber] @Bean wires. Publishes a `turnCompleted` payload on the per-profile
 * channel and asserts the listener receives it, then relays it through a real
 * [RealtimeRelayController.fanOut].
 */
@Testcontainers
class RealtimeRelayIT {
    private val profile = "default"
    private val channel = gameEventChannel(profile)

    @Test
    fun `turnCompleted published on the channel is delivered to the listener and relayed`() {
        assertTrue(redis.isRunning, "redis:7-alpine container must be running")

        val relay = RealtimeRelayController()
        val received = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        val listener = MessageListener { message, _ ->
            val body = String(message.body)
            received.set(body)
            relay.fanOut(body)
            latch.countDown()
        }
        container.addMessageListener(listener, ChannelTopic(channel))
        container.afterPropertiesSet()
        container.start()
        try {
            // wait for the subscription to be live before publishing
            val subscribeDeadline = System.currentTimeMillis() + 3000
            while (!container.isRunning && System.currentTimeMillis() < subscribeDeadline) {
                Thread.sleep(20)
            }

            val event = RealtimeEvent.TurnCompleted(
                at = "0200-01-01T00:00:00.000Z",
                lastTurnTime = "0199-12-01T00:00:00.000Z",
                year = 200,
                month = 1,
                turnNumber = 1,
            )
            val payload = WireJson.encodeToString(RealtimeEvent.serializer(), event)

            // publish until delivered (handles the pub/sub subscription warm-up race)
            val deadline = System.currentTimeMillis() + 5000
            var delivered = false
            while (System.currentTimeMillis() < deadline) {
                template.convertAndSend(channel, payload)
                if (latch.await(250, TimeUnit.MILLISECONDS)) {
                    delivered = true
                    break
                }
            }

            assertTrue(delivered, "listener received the turnCompleted message within the deadline")
            assertEquals(payload, received.get())
            val decoded = WireJson.decodeFromString(RealtimeEvent.serializer(), received.get()!!)
            assertTrue(decoded is RealtimeEvent.TurnCompleted)
        } finally {
            container.stop()
        }
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
