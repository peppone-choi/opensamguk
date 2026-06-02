package opensamguk.gameapi.web

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
) {

    data class HealthResponse(
        val status: String,
        val services: Map<String, String>,
    )

    @GetMapping
    fun health(): ResponseEntity<HealthResponse> {
        val services = mutableMapOf<String, String>()
        var allUp = true

        // DB check
        services["database"] = try {
            dataSource.connection.use { conn ->
                if (conn.isValid(3)) "up" else "down"
            }
        } catch (_: Exception) {
            allUp = false
            "down"
        }

        // Redis check
        services["redis"] = try {
            redisConnectionFactory.connection.use { conn ->
                val pong = conn.ping()
                if (pong == "PONG") "up" else "down"
            }
        } catch (_: Exception) {
            allUp = false
            "down"
        }

        services["self"] = "up"

        val status = if (allUp) "up" else "degraded"
        return ResponseEntity.ok(HealthResponse(status = status, services = services))
    }
}
