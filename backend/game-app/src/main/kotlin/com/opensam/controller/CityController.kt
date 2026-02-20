package com.opensam.controller

import com.opensam.dto.CityResponse
import com.opensam.service.CityService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class CityController(
    private val cityService: CityService,
) {
    @GetMapping("/worlds/{worldId}/cities")
    fun listByWorld(@PathVariable worldId: Long): ResponseEntity<List<CityResponse>> {
        return ResponseEntity.ok(cityService.listByWorld(worldId).map { CityResponse.from(it) })
    }

    @GetMapping("/cities/{id}")
    fun getById(@PathVariable id: Long): ResponseEntity<CityResponse> {
        val city = cityService.getById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(CityResponse.from(city))
    }

    @GetMapping("/nations/{nationId}/cities")
    fun listByNation(@PathVariable nationId: Long): ResponseEntity<List<CityResponse>> {
        return ResponseEntity.ok(cityService.listByNation(nationId).map { CityResponse.from(it) })
    }
}
