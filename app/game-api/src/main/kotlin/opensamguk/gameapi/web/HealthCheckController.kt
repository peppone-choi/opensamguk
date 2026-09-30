package opensamguk.gameapi.web

import java.time.Instant
import opensamguk.gameapi.read.TurnLoopHealth
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.dto.TurnLoopInfo
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

/**
 * Custom health endpoint that reports DB + Redis connectivity in a single JSON.
 *
 * Spring Boot Actuator (`/actuator/health`) is already enabled and provides
 * Kubernetes-style liveness/readiness probes. This controller adds a
 * user-facing `/health` that returns a flat summary for deploy scripts and
 * external monitoring.
 */
@RestController
@RequestMapping("/health")
class HealthCheckController(
    private val dataSource: DataSource,
    private val redisConnectionFactory: RedisConnectionFactory,
    private val world: WorldStateReadRepository,
) {

    data class HealthResponse(
        val status: String,
        val services: Map<String, String>,
        val world: WorldHealth,
        val serverTime: String,
    )

    data class WorldHealth(val lastTurnAt: String?, val lastTickExecutedAt: String?,
                           val stale: Boolean, val turnLoop: TurnLoopInfo)

    @GetMapping
    fun health(): ResponseEntity<HealthResponse> {
        val services = mutableMapOf<String, String>()

        // DB check
        services["database"] = try {
            dataSource.connection.use { conn ->
                if (conn.isValid(3)) "up" else "down"
            }
        } catch (_: Exception) {
            "down"
        }

        // Redis check
        services["redis"] = try {
            redisConnectionFactory.connection.use { conn ->
                val pong = conn.ping()
                if (pong == "PONG") "up" else "down"
            }
        } catch (_: Exception) {
            "down"
        }

        services["self"] = "up"

        val now = Instant.now()
        val observed = runCatching { world.findProcessWorld()?.let { TurnLoopHealth.observe(it, now) } }
            .getOrNull()
        val worldHealth = WorldHealth(observed?.lastTurnAt, observed?.lastTickExecutedAt, observed?.stale ?: true,
                                     TurnLoopInfo(observed?.state ?: TurnLoopHealth.State.STALLED, observed?.staleSeconds))
        val status = if (services.values.all { it == "up" } && observed?.healthy == true) "up" else "degraded"
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(HealthResponse(status = status, services = services, world = worldHealth, serverTime = now.toString()))
    }
}
