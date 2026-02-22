package com.opensam.gateway.orchestrator

import com.opensam.gateway.dto.AttachWorldProcessRequest
import com.opensam.gateway.dto.GameInstanceStatus

interface GameOrchestrator {

    fun attachWorld(worldId: Long, request: AttachWorldProcessRequest): GameInstanceStatus

    fun ensureVersion(request: AttachWorldProcessRequest): GameInstanceStatus

    fun detachWorld(worldId: Long): Boolean

    fun stopVersion(gameVersion: String): Boolean

    fun statuses(): List<GameInstanceStatus>

    fun shutdownAll()
}
