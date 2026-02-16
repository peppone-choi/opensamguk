package com.opensam.controller

import com.opensam.command.CommandResult
import com.opensam.dto.RealtimeExecuteRequest
import com.opensam.engine.RealtimeService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/realtime")
class RealtimeController(
    private val realtimeService: RealtimeService,
) {

    @PostMapping("/execute")
    fun execute(@RequestBody request: RealtimeExecuteRequest): ResponseEntity<CommandResult> {
        val result = realtimeService.submitCommand(request.generalId, request.actionCode, request.arg)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/status/{generalId}")
    fun status(@PathVariable generalId: Long): ResponseEntity<Any> {
        val status = realtimeService.getRealtimeStatus(generalId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(status)
    }
}
