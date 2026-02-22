package com.opensam.gateway.controller

import com.opensam.gateway.dto.AttachWorldProcessRequest
import com.opensam.gateway.dto.GameInstanceStatus
import com.opensam.gateway.orchestrator.GameOrchestrator
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/process")
class ProcessOrchestratorController(
    private val gameOrchestrator: GameOrchestrator,
) {
    @PostMapping("/versions/ensure")
    fun ensureVersion(@RequestBody request: AttachWorldProcessRequest): ResponseEntity<GameInstanceStatus> {
        return try {
            val status = gameOrchestrator.ensureVersion(request)
            ResponseEntity.ok(status)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    @PostMapping("/worlds/{worldId}/attach")
    fun attachWorld(
        @PathVariable worldId: Long,
        @RequestBody request: AttachWorldProcessRequest,
    ): ResponseEntity<GameInstanceStatus> {
        return try {
            val status = gameOrchestrator.attachWorld(worldId, request)
            ResponseEntity.ok(status)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    @PostMapping("/worlds/{worldId}/detach")
    fun detachWorld(@PathVariable worldId: Long): ResponseEntity<Void> {
        val detached = gameOrchestrator.detachWorld(worldId)
        return if (detached) {
            ResponseEntity.ok().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/instances")
    fun instances(): ResponseEntity<List<GameInstanceStatus>> {
        return ResponseEntity.ok(gameOrchestrator.statuses())
    }
}
