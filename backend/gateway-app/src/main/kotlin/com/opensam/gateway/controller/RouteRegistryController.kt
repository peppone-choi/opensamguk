package com.opensam.gateway.controller

import com.opensam.gateway.dto.AttachWorldRequest
import com.opensam.gateway.dto.WorldRouteResponse
import com.opensam.gateway.service.WorldRouteRegistry
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/worlds")
class RouteRegistryController(
    private val worldRouteRegistry: WorldRouteRegistry,
) {
    @PostMapping("/{worldId}/attach")
    fun attachWorld(
        @PathVariable worldId: Long,
        @RequestBody request: AttachWorldRequest,
    ): ResponseEntity<Void> {
        worldRouteRegistry.attach(worldId, request.baseUrl)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{worldId}/detach")
    fun detachWorld(@PathVariable worldId: Long): ResponseEntity<Void> {
        worldRouteRegistry.detach(worldId)
        return ResponseEntity.ok().build()
    }

    @GetMapping("/routes")
    fun listRoutes(): ResponseEntity<List<WorldRouteResponse>> {
        val routes = worldRouteRegistry.snapshot().map { (worldId, baseUrl) ->
            WorldRouteResponse(worldId = worldId, baseUrl = baseUrl)
        }
        return ResponseEntity.ok(routes)
    }
}
