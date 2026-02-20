package com.opensam.gateway.orchestrator

import com.opensam.gateway.dto.AttachWorldProcessRequest
import com.opensam.gateway.service.WorldService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class WorldActivationBootstrap(
    private val worldService: WorldService,
    private val gameProcessOrchestrator: GameProcessOrchestrator,
    @Value("\${gateway.orchestrator.restore-active-worlds:true}")
    private val restoreActiveWorlds: Boolean,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(WorldActivationBootstrap::class.java)

    override fun run(args: ApplicationArguments) {
        if (!restoreActiveWorlds) {
            log.info("Gateway world restore is disabled")
            return
        }

        val worlds = worldService.listWorlds()
        val activeWorlds = worlds.filter { isGatewayActive(it.meta["gatewayActive"]) }

        if (activeWorlds.isEmpty()) {
            log.info("No previously active worlds to restore")
            return
        }

        activeWorlds.forEach { world ->
            try {
                gameProcessOrchestrator.attachWorld(
                    worldId = world.id.toLong(),
                    request = AttachWorldProcessRequest(
                        commitSha = world.commitSha,
                        gameVersion = world.gameVersion,
                    ),
                )
                log.info(
                    "Restored world={} commitSha={} gameVersion={}",
                    world.id,
                    world.commitSha,
                    world.gameVersion,
                )
            } catch (e: Exception) {
                log.warn(
                    "Failed to restore world={} commitSha={} gameVersion={}",
                    world.id,
                    world.commitSha,
                    world.gameVersion,
                    e,
                )
            }
        }
    }

    private fun isGatewayActive(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
    }
}
