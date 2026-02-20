package com.opensam.service

import com.opensam.engine.TurnDaemon
import org.springframework.stereotype.Service

@Service
class TurnManagementService(
    private val turnDaemon: TurnDaemon,
) {
    fun getStatus(): String = turnDaemon.getStatus().name

    fun manualRun(): String {
        turnDaemon.manualRun()
        return "triggered"
    }

    fun pause(): String {
        turnDaemon.pause()
        return turnDaemon.getStatus().name
    }

    fun resume(): String {
        turnDaemon.resume()
        return turnDaemon.getStatus().name
    }
}
