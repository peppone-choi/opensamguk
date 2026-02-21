package com.opensam.gateway.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opensam.shared.model.ScenarioData
import com.opensam.shared.model.ScenarioInfo
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class StaticDataController(
    private val objectMapper: ObjectMapper,
) {
    private val scenarios = mutableMapOf<String, ScenarioData>()

    @GetMapping("/scenarios")
    fun listScenarios(): ResponseEntity<List<ScenarioInfo>> {
        loadAllScenarios()
        val list = scenarios.map { (code, data) ->
            ScenarioInfo(code, data.title, data.startYear)
        }.sortedBy { it.code }
        return ResponseEntity.ok(list)
    }

    @GetMapping("/maps/{mapName}")
    fun getMapData(@PathVariable mapName: String): ResponseEntity<JsonNode> {
        val resource = ClassPathResource("data/maps/$mapName.json")
        if (!resource.exists()) {
            return ResponseEntity.notFound().build()
        }
        val json = objectMapper.readTree(resource.inputStream)
        return ResponseEntity.ok(json)
    }

    private fun loadAllScenarios() {
        if (scenarios.isNotEmpty()) return
        val resolver = PathMatchingResourcePatternResolver()
        val resources = resolver.getResources("classpath:data/scenarios/scenario_*.json")
        for (resource in resources) {
            val filename = resource.filename ?: continue
            val code = filename.removePrefix("scenario_").removeSuffix(".json")
            val data: ScenarioData = objectMapper.readValue(resource.inputStream)
            scenarios[code] = data
        }
    }
}
