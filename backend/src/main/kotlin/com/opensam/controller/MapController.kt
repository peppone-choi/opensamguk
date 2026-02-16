package com.opensam.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class MapController(
    private val objectMapper: ObjectMapper,
) {
    @GetMapping("/maps/{mapName}")
    fun getMapData(@PathVariable mapName: String): ResponseEntity<JsonNode> {
        val resource = ClassPathResource("data/maps/$mapName.json")
        if (!resource.exists()) {
            return ResponseEntity.notFound().build()
        }
        val json = objectMapper.readTree(resource.inputStream)
        return ResponseEntity.ok(json)
    }
}
