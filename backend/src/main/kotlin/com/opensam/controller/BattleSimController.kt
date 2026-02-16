package com.opensam.controller

import com.opensam.dto.SimulateRequest
import com.opensam.dto.SimulateResult
import com.opensam.service.BattleSimService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/battle")
class BattleSimController(
    private val battleSimService: BattleSimService,
) {
    @PostMapping("/simulate")
    fun simulate(@RequestBody request: SimulateRequest): ResponseEntity<SimulateResult> {
        return ResponseEntity.ok(battleSimService.simulate(request))
    }
}
