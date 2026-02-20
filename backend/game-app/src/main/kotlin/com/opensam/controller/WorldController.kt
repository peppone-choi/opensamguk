package com.opensam.controller

import com.opensam.dto.CreateWorldRequest
import com.opensam.dto.ResetWorldRequest
import com.opensam.dto.WorldStateResponse
import com.opensam.service.AdminAuthorizationService
import com.opensam.service.ScenarioService
import com.opensam.service.WorldService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class WorldController(
    private val scenarioService: ScenarioService,
    private val worldService: WorldService,
    private val adminAuthorizationService: AdminAuthorizationService,
) {
    private fun currentLoginId(): String? = SecurityContextHolder.getContext().authentication?.name

    @GetMapping("/worlds")
    fun listWorlds(): ResponseEntity<List<WorldStateResponse>> {
        return ResponseEntity.ok(worldService.listWorlds().map { WorldStateResponse.from(it) })
    }

    @GetMapping("/worlds/{id}")
    fun getWorld(@PathVariable id: Short): ResponseEntity<WorldStateResponse> {
        val world = worldService.getWorld(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(WorldStateResponse.from(world))
    }

    @PostMapping("/worlds")
    fun createWorld(@Valid @RequestBody request: CreateWorldRequest): ResponseEntity<WorldStateResponse> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        try {
            adminAuthorizationService.requireGlobalAdmin(loginId)
        } catch (_: AccessDeniedException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val world = scenarioService.initializeWorld(
            scenarioCode = request.scenarioCode,
            tickSeconds = request.tickSeconds,
            commitSha = request.commitSha ?: "local",
            gameVersion = request.gameVersion ?: "dev",
        )
        if (!request.name.isNullOrBlank()) {
            world.name = request.name
            worldService.save(world)
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(WorldStateResponse.from(world))
    }

    @DeleteMapping("/worlds/{id}")
    fun deleteWorld(@PathVariable id: Short): ResponseEntity<Void> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        try {
            adminAuthorizationService.requireGlobalAdmin(loginId)
        } catch (_: AccessDeniedException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        worldService.deleteWorld(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/worlds/{id}/reset")
    fun resetWorld(
        @PathVariable id: Short,
        @RequestBody(required = false) body: ResetWorldRequest?,
    ): ResponseEntity<WorldStateResponse> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        try {
            adminAuthorizationService.requireGlobalAdmin(loginId)
        } catch (_: AccessDeniedException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val world = worldService.getWorld(id)
            ?: return ResponseEntity.notFound().build()
        val scenarioCode = body?.scenarioCode ?: world.scenarioCode
        val reset = scenarioService.initializeWorld(
            scenarioCode = scenarioCode,
            tickSeconds = world.tickSeconds.toInt(),
            commitSha = world.commitSha,
            gameVersion = world.gameVersion,
        )
        reset.name = world.name
        worldService.deleteWorld(id)
        return ResponseEntity.ok(WorldStateResponse.from(reset))
    }
}
