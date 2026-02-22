package com.opensam.gateway.controller

import com.opensam.gateway.dto.WorldStateResponse
import com.opensam.gateway.dto.AttachWorldProcessRequest
import com.opensam.gateway.dto.ActivateWorldRequest
import com.opensam.gateway.orchestrator.GameOrchestrator
import com.opensam.gateway.service.WorldService
import com.opensam.shared.dto.CreateWorldRequest
import com.opensam.shared.dto.ResetWorldRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

@RestController
@RequestMapping("/api/worlds")
class WorldController(
    private val worldService: WorldService,
    private val gameOrchestrator: GameOrchestrator,
    private val webClientBuilder: WebClient.Builder,
) {
    @GetMapping
    fun listWorlds(): ResponseEntity<List<WorldStateResponse>> {
        return ResponseEntity.ok(worldService.listWorlds().map { WorldStateResponse.from(it) })
    }

    @GetMapping("/{id}")
    fun getWorld(@PathVariable id: Short): ResponseEntity<WorldStateResponse> {
        val world = worldService.getWorld(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(WorldStateResponse.from(world))
    }

    @PostMapping
    fun createWorld(
        @Valid @RequestBody request: CreateWorldRequest,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<WorldStateResponse> {
        val commitSha = request.commitSha?.takeIf { it.isNotBlank() } ?: "local"
        val gameVersion = request.gameVersion?.takeIf { it.isNotBlank() } ?: "dev"

        return try {
            val instance = gameOrchestrator.ensureVersion(
                AttachWorldProcessRequest(
                    commitSha = commitSha,
                    gameVersion = gameVersion,
                ),
            )

            val created = createWorldViaGameInstance(
                baseUrl = instance.baseUrl,
                request = request.copy(commitSha = commitSha, gameVersion = gameVersion),
                authorizationHeader = httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION),
            )

            gameOrchestrator.attachWorld(
                worldId = created.id.toLong(),
                request = AttachWorldProcessRequest(
                    commitSha = created.commitSha,
                    gameVersion = created.gameVersion,
                ),
            )

            val persisted = worldService.getWorld(created.id)
            if (persisted != null) {
                worldService.updateVersionAndActivation(
                    world = persisted,
                    commitSha = created.commitSha,
                    gameVersion = created.gameVersion,
                    active = true,
                )
            }

            val response = worldService.getWorld(created.id)?.let { WorldStateResponse.from(it) } ?: created
            ResponseEntity.status(HttpStatus.CREATED).body(response)
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        } catch (_: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        } catch (_: WebClientResponseException.Unauthorized) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        } catch (_: WebClientResponseException.Forbidden) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        } catch (_: WebClientResponseException.BadRequest) {
            ResponseEntity.badRequest().build()
        } catch (_: WebClientResponseException.NotFound) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } catch (_: WebClientResponseException) {
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        }
    }

    @PostMapping("/{id}/activate")
    fun activateWorld(
        @PathVariable id: Short,
        @RequestBody(required = false) request: ActivateWorldRequest?,
    ): ResponseEntity<Void> {
        val world = worldService.getWorld(id) ?: return ResponseEntity.notFound().build()

        val commitSha = request?.commitSha?.takeIf { it.isNotBlank() } ?: world.commitSha
        val gameVersion = request?.gameVersion?.takeIf { it.isNotBlank() } ?: world.gameVersion

        return try {
            gameOrchestrator.attachWorld(
                worldId = id.toLong(),
                request = AttachWorldProcessRequest(
                    commitSha = commitSha,
                    gameVersion = gameVersion,
                    jarPath = request?.jarPath,
                    port = request?.port,
                    javaCommand = request?.javaCommand ?: "java",
                    imageTag = request?.imageTag,
                ),
            )
            worldService.updateVersionAndActivation(world, commitSha, gameVersion, active = true)
            ResponseEntity.status(HttpStatus.ACCEPTED).build()
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        } catch (_: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    @PostMapping("/{id}/deactivate")
    fun deactivateWorld(@PathVariable id: Short): ResponseEntity<Void> {
        val world = worldService.getWorld(id) ?: return ResponseEntity.notFound().build()
        val detached = gameOrchestrator.detachWorld(id.toLong())
        return if (detached) {
            worldService.markActivation(world, active = false)
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/{id}/reset")
    fun resetWorld(
        @PathVariable id: Short,
        @RequestBody(required = false) body: ResetWorldRequest?,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<WorldStateResponse> {
        val world = worldService.getWorld(id) ?: return ResponseEntity.notFound().build()

        return try {
            val instance = gameOrchestrator.ensureVersion(
                AttachWorldProcessRequest(
                    commitSha = world.commitSha,
                    gameVersion = world.gameVersion,
                ),
            )

            val resetWorld = resetWorldViaGameInstance(
                baseUrl = instance.baseUrl,
                worldId = id,
                body = body,
                authorizationHeader = httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION),
            )

            gameOrchestrator.detachWorld(id.toLong())
            gameOrchestrator.attachWorld(
                worldId = resetWorld.id.toLong(),
                request = AttachWorldProcessRequest(
                    commitSha = resetWorld.commitSha,
                    gameVersion = resetWorld.gameVersion,
                ),
            )

            worldService.getWorld(resetWorld.id)?.let {
                worldService.updateVersionAndActivation(
                    world = it,
                    commitSha = resetWorld.commitSha,
                    gameVersion = resetWorld.gameVersion,
                    active = true,
                )
            }

            val response = worldService.getWorld(resetWorld.id)?.let { WorldStateResponse.from(it) } ?: resetWorld
            ResponseEntity.ok(response)
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        } catch (_: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        } catch (_: WebClientResponseException.Unauthorized) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        } catch (_: WebClientResponseException.Forbidden) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        } catch (_: WebClientResponseException.BadRequest) {
            ResponseEntity.badRequest().build()
        } catch (_: WebClientResponseException.NotFound) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } catch (_: WebClientResponseException) {
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteWorld(
        @PathVariable id: Short,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Void> {
        val world = worldService.getWorld(id) ?: return ResponseEntity.notFound().build()

        return try {
            val instance = gameOrchestrator.ensureVersion(
                AttachWorldProcessRequest(
                    commitSha = world.commitSha,
                    gameVersion = world.gameVersion,
                ),
            )

            deleteWorldViaGameInstance(
                baseUrl = instance.baseUrl,
                worldId = id,
                authorizationHeader = httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION),
            )

            gameOrchestrator.detachWorld(id.toLong())
            ResponseEntity.noContent().build()
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        } catch (_: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        } catch (_: WebClientResponseException.Unauthorized) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        } catch (_: WebClientResponseException.Forbidden) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        } catch (_: WebClientResponseException.NotFound) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } catch (_: WebClientResponseException) {
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        }
    }

    private fun createWorldViaGameInstance(
        baseUrl: String,
        request: CreateWorldRequest,
        authorizationHeader: String?,
    ): WorldStateResponse {
        return webClientBuilder
            .build()
            .post()
            .uri("$baseUrl/api/worlds")
            .headers { headers ->
                if (!authorizationHeader.isNullOrBlank()) {
                    headers[HttpHeaders.AUTHORIZATION] = listOf(authorizationHeader)
                }
            }
            .bodyValue(request)
            .retrieve()
            .bodyToMono(WorldStateResponse::class.java)
            .block() ?: throw IllegalStateException("Empty response from game world creation")
    }

    private fun resetWorldViaGameInstance(
        baseUrl: String,
        worldId: Short,
        body: ResetWorldRequest?,
        authorizationHeader: String?,
    ): WorldStateResponse {
        return webClientBuilder
            .build()
            .post()
            .uri("$baseUrl/api/worlds/$worldId/reset")
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers ->
                if (!authorizationHeader.isNullOrBlank()) {
                    headers[HttpHeaders.AUTHORIZATION] = listOf(authorizationHeader)
                }
            }
            .bodyValue(body ?: ResetWorldRequest())
            .retrieve()
            .bodyToMono(WorldStateResponse::class.java)
            .block() ?: throw IllegalStateException("Empty response from game world reset")
    }

    private fun deleteWorldViaGameInstance(
        baseUrl: String,
        worldId: Short,
        authorizationHeader: String?,
    ) {
        webClientBuilder
            .build()
            .delete()
            .uri("$baseUrl/api/worlds/$worldId")
            .headers { headers ->
                if (!authorizationHeader.isNullOrBlank()) {
                    headers[HttpHeaders.AUTHORIZATION] = listOf(authorizationHeader)
                }
            }
            .retrieve()
            .toBodilessEntity()
            .block() ?: throw IllegalStateException("Empty response from game world delete")
    }
}
