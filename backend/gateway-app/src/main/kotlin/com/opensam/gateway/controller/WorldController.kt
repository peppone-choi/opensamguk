package com.opensam.gateway.controller

import com.opensam.gateway.dto.WorldStateResponse
import com.opensam.gateway.dto.AttachWorldProcessRequest
import com.opensam.gateway.dto.ActivateWorldRequest
import com.opensam.gateway.orchestrator.GameProcessOrchestrator
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
    private val gameProcessOrchestrator: GameProcessOrchestrator,
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
            val instance = gameProcessOrchestrator.ensureVersion(
                AttachWorldProcessRequest(
                    commitSha = commitSha,
                    gameVersion = gameVersion,
                ),
            )

            val created = createWorldViaGameInstance(
                port = instance.port,
                request = request.copy(commitSha = commitSha, gameVersion = gameVersion),
                authorizationHeader = httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION),
            )

            gameProcessOrchestrator.attachWorld(
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
            gameProcessOrchestrator.attachWorld(
                worldId = id.toLong(),
                request = AttachWorldProcessRequest(
                    commitSha = commitSha,
                    gameVersion = gameVersion,
                    jarPath = request?.jarPath,
                    port = request?.port,
                    javaCommand = request?.javaCommand ?: "java",
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
        val detached = gameProcessOrchestrator.detachWorld(id.toLong())
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
            val instance = gameProcessOrchestrator.ensureVersion(
                AttachWorldProcessRequest(
                    commitSha = world.commitSha,
                    gameVersion = world.gameVersion,
                ),
            )

            val resetWorld = resetWorldViaGameInstance(
                port = instance.port,
                worldId = id,
                body = body,
                authorizationHeader = httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION),
            )

            gameProcessOrchestrator.detachWorld(id.toLong())
            gameProcessOrchestrator.attachWorld(
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
            val instance = gameProcessOrchestrator.ensureVersion(
                AttachWorldProcessRequest(
                    commitSha = world.commitSha,
                    gameVersion = world.gameVersion,
                ),
            )

            deleteWorldViaGameInstance(
                port = instance.port,
                worldId = id,
                authorizationHeader = httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION),
            )

            gameProcessOrchestrator.detachWorld(id.toLong())
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
        port: Int,
        request: CreateWorldRequest,
        authorizationHeader: String?,
    ): WorldStateResponse {
        return webClientBuilder
            .build()
            .post()
            .uri("http://127.0.0.1:$port/api/worlds")
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
        port: Int,
        worldId: Short,
        body: ResetWorldRequest?,
        authorizationHeader: String?,
    ): WorldStateResponse {
        return webClientBuilder
            .build()
            .post()
            .uri("http://127.0.0.1:$port/api/worlds/$worldId/reset")
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
        port: Int,
        worldId: Short,
        authorizationHeader: String?,
    ) {
        webClientBuilder
            .build()
            .delete()
            .uri("http://127.0.0.1:$port/api/worlds/$worldId")
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
