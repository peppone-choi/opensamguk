package com.opensam.controller

import com.opensam.model.ScenarioInfo
import com.opensam.service.ScenarioService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class ScenarioController(
    private val scenarioService: ScenarioService,
) {
    @GetMapping("/scenarios")
    fun listScenarios(): ResponseEntity<List<ScenarioInfo>> {
        return ResponseEntity.ok(scenarioService.listScenarios())
    }
}
