package com.opensam.controller

import com.opensam.dto.NationResponse
import com.opensam.service.NationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class NationController(
    private val nationService: NationService,
) {
    @GetMapping("/worlds/{worldId}/nations")
    fun listByWorld(@PathVariable worldId: Long): ResponseEntity<List<NationResponse>> {
        return ResponseEntity.ok(nationService.listByWorld(worldId).map { NationResponse.from(it) })
    }

    @GetMapping("/nations/{id}")
    fun getById(@PathVariable id: Long): ResponseEntity<NationResponse> {
        val nation = nationService.getById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(NationResponse.from(nation))
    }
}
