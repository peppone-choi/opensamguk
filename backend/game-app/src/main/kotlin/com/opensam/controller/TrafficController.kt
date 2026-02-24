package com.opensam.controller

import com.opensam.service.TrafficService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class TrafficController(
    private val trafficService: TrafficService,
) {
    @GetMapping("/worlds/{worldId}/traffic")
    fun getTraffic(@PathVariable worldId: Long): ResponseEntity<TrafficService.TrafficResponse> {
        return ResponseEntity.ok(trafficService.getTraffic(worldId))
    }
}
