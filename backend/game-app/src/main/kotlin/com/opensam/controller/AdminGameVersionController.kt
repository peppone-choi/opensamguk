package com.opensam.controller

import com.opensam.repository.WorldStateRepository
import com.opensam.service.AdminAuthorizationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

/**
 * Admin endpoints for game version management.
 * Lists deployed world instances and supports deploy/stop actions.
 * Stub implementation: all worlds run in the same JVM process.
 */
@RestController
@RequestMapping("/api/admin/game-versions")
class AdminGameVersionController(
    private val worldStateRepository: WorldStateRepository,
    private val adminAuthorizationService: AdminAuthorizationService,
) {
    data class GameVersionInfo(
        val commitSha: String,
        val gameVersion: String,
        val jarPath: String,
        val port: Int,
        val worldIds: List<Int>,
        val alive: Boolean,
        val pid: Long,
        val baseUrl: String,
        val containerId: String?,
        val imageTag: String?,
    )

    private fun currentLoginId(): String? = SecurityContextHolder.getContext().authentication?.name

    @GetMapping
    fun listVersions(): ResponseEntity<List<GameVersionInfo>> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            adminAuthorizationService.requireGlobalAdmin(loginId)
            val worlds = worldStateRepository.findAll()
            val pid = ProcessHandle.current().pid()
            val info = GameVersionInfo(
                commitSha = worlds.firstOrNull()?.commitSha ?: "local",
                gameVersion = worlds.firstOrNull()?.gameVersion ?: "dev",
                jarPath = System.getProperty("java.class.path", ""),
                port = 8080,
                worldIds = worlds.map { it.id.toInt() },
                alive = true,
                pid = pid,
                baseUrl = "http://localhost:8080",
                containerId = null,
                imageTag = null,
            )
            ResponseEntity.ok(listOf(info))
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @PostMapping
    fun deploy(@RequestBody data: Map<String, Any>): ResponseEntity<Map<String, String>> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            adminAuthorizationService.requireGlobalAdmin(loginId)
            // In embedded mode, all worlds run in the same process.
            // Deployment requires an external orchestrator; return informational response.
            ResponseEntity.ok(mapOf("status" to "embedded", "message" to "Embedded mode: restart the process with the new jar to upgrade."))
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @DeleteMapping("/{version}")
    fun stop(@PathVariable version: String): ResponseEntity<Void> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            adminAuthorizationService.requireGlobalAdmin(loginId)
            // Embedded mode: cannot stop individual versions; no-op.
            ResponseEntity.noContent().build()
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }
}
