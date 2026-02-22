package com.opensam.gateway.controller

import com.opensam.gateway.dto.AttachWorldProcessRequest
import com.opensam.gateway.dto.DeployGameVersionRequest
import com.opensam.gateway.dto.GameInstanceStatus
import com.opensam.gateway.orchestrator.GameOrchestrator
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/game-versions")
class GameVersionController(
    private val gameOrchestrator: GameOrchestrator,
) {
    @GetMapping
    fun listVersions(): ResponseEntity<List<GameInstanceStatus>> {
        return ResponseEntity.ok(gameOrchestrator.statuses())
    }

    @PostMapping
    fun deployVersion(@RequestBody request: DeployGameVersionRequest): ResponseEntity<GameInstanceStatus> {
        return try {
            val status = gameOrchestrator.ensureVersion(
                AttachWorldProcessRequest(
                    commitSha = request.commitSha ?: "local",
                    gameVersion = request.gameVersion,
                    imageTag = request.imageTag,
                ),
            )
            ResponseEntity.status(HttpStatus.CREATED).body(status)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    @DeleteMapping("/{version}")
    fun stopVersion(@PathVariable version: String): ResponseEntity<Void> {
        val stopped = gameOrchestrator.stopVersion(version)
        return if (stopped) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }
}
