package opensamguk.gameapi.sse

import opensamguk.common.wire.gameEventChannel
import opensamguk.gameapi.config.GameApiProcessWorld
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * Subscribes to the per-profile realtime pub/sub channel and relays each message into the SSE
 * fan-out. The channel name is built from the `:common` [gameEventChannel] using the configured
 * profile (default `che:scenario_2`).
 *
 * The container is created with `autoStartup = false` and started from a background daemon thread
 * that retries until Redis is reachable. This keeps a transiently-unavailable Redis at boot from
 * aborting the application context (Spring Data Redis's lifecycle `start()` otherwise propagates
 * the initial connection refusal), and lets the JPA-only boot test load its context without a
 * Redis container while production reconnects as soon as Redis is up.
 */
@Configuration
class RealtimeSubscriber(
    @Value("\${opensamguk.profile:che:scenario_2}") private val profile: String,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId

    private val log = LoggerFactory.getLogger(RealtimeSubscriber::class.java)

    @Bean
    fun realtimeListenerContainer(
        connectionFactory: RedisConnectionFactory,
        relay: RealtimeRelayController,
    ): RedisMessageListenerContainer {
        // Override isAutoStartup() to false so the context-refresh lifecycle does NOT drive the
        // (potentially failing) initial connect; the retrying daemon below starts it once
        // Spring has finished initializing the bean (afterPropertiesSet).
        val container = object : RedisMessageListenerContainer() {
            override fun isAutoStartup(): Boolean = false

            override fun afterPropertiesSet() {
                super.afterPropertiesSet()
                startInBackground(this)
            }
        }
        container.setConnectionFactory(connectionFactory)
        container.setSubscriptionExecutor(SimpleAsyncTaskExecutor("realtime-sub-"))
        container.setTaskExecutor(SimpleAsyncTaskExecutor("realtime-recv-"))
        container.setRecoveryInterval(5000L)
        val listener = MessageListener { message, _ -> relay.fanOut(String(message.body)) }
        container.addMessageListener(listener, ChannelTopic(gameEventChannel(profile, worldId)))
        return container
    }

    private fun startInBackground(container: RedisMessageListenerContainer) {
        val thread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    container.start()
                    return@Thread
                } catch (ex: Exception) {
                    log.warn("realtime listener start failed; retrying in 5s: {}", ex.message)
                    try {
                        Thread.sleep(5000L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }
        }
        thread.name = "realtime-listener-bootstrap"
        thread.isDaemon = true
        thread.start()
    }
}
