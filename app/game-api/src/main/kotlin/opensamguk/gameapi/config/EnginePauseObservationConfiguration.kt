package opensamguk.gameapi.config

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.read.EnginePauseCollectorSettings
import opensamguk.gameapi.read.EnginePauseObservationCollector
import opensamguk.gameapi.read.InternalEnginePauseSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.time.Clock
import java.time.Duration

/** Operator opt-in only; all identity and timing inputs must be supplied explicitly. */
@Configuration
@ConditionalOnProperty(prefix = "turn-daemon-observation", name = ["enabled"], havingValue = "true")
class EnginePauseObservationConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun enginePauseObservationCollector(
        processWorld: GameApiProcessWorld,
        mapper: ObjectMapper,
        @Value("\${turn-daemon-observation.origin}") origin: String,
        @Value("\${turn-daemon-observation.server-id}") serverId: String,
        @Value("\${turn-daemon-observation.world-id}") worldId: Int,
        @Value("\${turn-daemon-observation.tick-seconds}") tickSeconds: Int,
        @Value("\${turn-daemon-observation.poll-millis}") pollMillis: Long,
        @Value("\${turn-daemon-observation.deadline-millis}") deadlineMillis: Long,
        @Value("\${turn-daemon-observation.max-age-millis}") maxAgeMillis: Long,
    ): EnginePauseObservationCollector {
        require(worldId == processWorld.worldId.value) { "Pause observation world must match the process world" }
        val uri = try { URI(origin) } catch (_: Exception) { error("Invalid pause observation origin") }
        val settings = EnginePauseCollectorSettings(uri, serverId, worldId, tickSeconds,
            Duration.ofMillis(pollMillis), Duration.ofMillis(deadlineMillis), Duration.ofMillis(maxAgeMillis))
        val clock = Clock.systemUTC()
        return EnginePauseObservationCollector(settings, InternalEnginePauseSource(settings, clock, mapper), clock)
    }
}
