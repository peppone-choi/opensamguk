package com.opensam.service

import com.opensam.repository.WorldStateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Implements the auto-reset check logic from legacy j_autoreset.php.
 *
 * The legacy system checks a `reserved_open` table for a scheduled reset date,
 * then decides whether to close the server early based on game state (unified/stopped)
 * and proximity to the reset time. When the reset time arrives, it triggers a full
 * scenario rebuild.
 *
 * In the Kotlin backend, scheduled reset info is stored in WorldState.meta under:
 *   - "reservedResetDate": ISO-8601 datetime string for the scheduled reset
 *   - "reservedResetOptions": map of reset options (scenario, turnterm, etc.)
 *
 * The "isunited" game state is stored in WorldState.config:
 *   - config["isunited"]: 0 = normal play, 1 = unified (천통), 2 = stopped/ended
 *
 * The "turntime" (last turn time) is WorldState.updatedAt.
 */
@Service
class AutoResetService(
    private val worldStateRepository: WorldStateRepository,
    private val scenarioService: ScenarioService,
) {
    private val log = LoggerFactory.getLogger(AutoResetService::class.java)

    data class AutoResetResult(
        val result: Boolean = true,
        val affected: Int = 0,
        val status: String = "not_yet",
        val info: Map<String, Any>? = null,
    )

    /**
     * Check whether the world should be closed or reset based on the reserved schedule.
     */
    @Transactional
    fun checkAutoReset(worldId: Long): AutoResetResult {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null)
            ?: return AutoResetResult(status = "world_not_found")

        // Check for reserved reset configuration
        val reservedDateStr = world.meta["reservedResetDate"] as? String
            ?: return AutoResetResult(status = "no_reserved")

        val reservedDate: OffsetDateTime = try {
            OffsetDateTime.parse(reservedDateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (e: Exception) {
            try {
                // Try parsing as local datetime and assume UTC
                java.time.LocalDateTime.parse(reservedDateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atOffset(java.time.ZoneOffset.UTC)
            } catch (_: Exception) {
                log.warn("Invalid reservedResetDate format: {}", reservedDateStr)
                return AutoResetResult(status = "invalid_date")
            }
        }

        val now = OffsetDateTime.now()
        val isUnited = (world.config["isunited"] as? Number)?.toInt() ?: 0
        val lastTurn = world.updatedAt

        var status = "not_yet"

        // Check if server should be closed early based on game state and timing
        val serverClosed = world.config["serverClosed"] as? Boolean ?: false

        if (!serverClosed) {
            val timeSinceLastTurn = Duration.between(lastTurn, now).seconds
            val timeUntilReset = Duration.between(now, reservedDate).seconds

            if (isUnited == 2 && timeSinceLastTurn > timeUntilReset) {
                // Game is stopped & past midpoint → close server
                closeServer(world)
                status = "closed"
            } else if (isUnited > 0 && timeSinceLastTurn > timeUntilReset * 2) {
                // Unified & past 2/3 point → close server
                closeServer(world)
                status = "closed"
            } else if (timeUntilReset <= 600) {
                // Less than 10 minutes until reset → close server regardless
                closeServer(world)
                status = "closed"
            }
        } else {
            status = "closed"
        }

        // If reset time hasn't arrived yet, return current status
        if (now.isBefore(reservedDate)) {
            return AutoResetResult(status = status)
        }

        // Reset time has arrived — trigger the reset
        @Suppress("UNCHECKED_CAST")
        val options = world.meta["reservedResetOptions"] as? Map<String, Any>
            ?: return AutoResetResult(status = "no_options")

        val scenarioCode = options["scenario"] as? String ?: world.scenarioCode
        val tickSeconds = (options["turnterm"] as? Number)?.toInt() ?: world.tickSeconds

        return try {
            log.info("Auto-reset triggered for world {} with scenario {}", worldId, scenarioCode)

            // Re-initialize the world with the new scenario
            val newWorld = scenarioService.initializeWorld(
                scenarioCode = scenarioCode,
                tickSeconds = tickSeconds,
                commitSha = world.commitSha,
                gameVersion = world.gameVersion,
            )

            // Clear the reserved reset from meta
            world.meta.remove("reservedResetDate")
            world.meta.remove("reservedResetOptions")
            world.config["serverClosed"] = false

            // Open the server
            openServer(world)

            AutoResetResult(
                affected = 1,
                status = "reset_complete",
                info = mapOf(
                    "newWorldId" to newWorld.id.toInt(),
                    "scenario" to scenarioCode,
                ),
            )
        } catch (e: Exception) {
            log.error("Auto-reset failed for world {}: {}", worldId, e.message, e)
            AutoResetResult(
                result = false,
                status = "reset_failed",
                info = mapOf("error" to (e.message ?: "unknown")),
            )
        }
    }

    private fun closeServer(world: com.opensam.entity.WorldState) {
        world.config["serverClosed"] = true
        world.config["locked"] = true
        worldStateRepository.save(world)
        log.info("Server closed for world {}", world.id)
    }

    private fun openServer(world: com.opensam.entity.WorldState) {
        world.config["serverClosed"] = false
        world.config["locked"] = false
        worldStateRepository.save(world)
        log.info("Server opened for world {}", world.id)
    }
}
