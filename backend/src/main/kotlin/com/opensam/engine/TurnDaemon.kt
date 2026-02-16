package com.opensam.engine

import com.opensam.repository.WorldStateRepository
import com.opensam.service.GameEventService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class TurnDaemon(
    private val turnService: TurnService,
    private val realtimeService: RealtimeService,
    private val gameEventService: GameEventService,
    private val worldStateRepository: WorldStateRepository,
) {
    enum class DaemonState { IDLE, RUNNING, FLUSHING, PAUSED, STOPPING }

    @Volatile
    private var state = DaemonState.IDLE

    private val logger = LoggerFactory.getLogger(TurnDaemon::class.java)

    @Scheduled(fixedDelayString = "\${app.turn.interval-ms:300000}")
    fun tick() {
        if (state != DaemonState.IDLE) return
        state = DaemonState.RUNNING
        try {
            val worlds = worldStateRepository.findAll()
            for (world in worlds) {
                try {
                    if (world.realtimeMode) {
                        realtimeService.processCompletedCommands(world)
                        realtimeService.regenerateCommandPoints(world)
                    } else {
                        val prevMonth = world.currentMonth
                        turnService.processWorld(world)
                        if (world.currentMonth != prevMonth) {
                            gameEventService.broadcastTurnAdvance(
                                world.id.toLong(),
                                world.currentYear.toInt(),
                                world.currentMonth.toInt()
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error processing world ${world.id}: ${e.message}", e)
                }
            }
        } finally {
            state = DaemonState.FLUSHING
            try {
                worldStateRepository.flush()
            } finally {
                state = DaemonState.IDLE
            }
        }
    }

    fun pause() { state = DaemonState.PAUSED }
    fun resume() { state = DaemonState.IDLE }
    fun getStatus() = state
    fun manualRun() {
        if (state == DaemonState.IDLE) tick()
    }
}
