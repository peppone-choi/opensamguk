package com.opensam.controller

import com.opensam.service.TurnManagementService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/turns")
class TurnController(
    private val turnManagementService: TurnManagementService,
) {
    @GetMapping("/status")
    fun getStatus(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("state" to turnManagementService.getStatus()))
    }

    @PostMapping("/run")
    fun manualRun(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("result" to turnManagementService.manualRun()))
    }

    @PostMapping("/pause")
    fun pause(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("state" to turnManagementService.pause()))
    }

    @PostMapping("/resume")
    fun resume(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("state" to turnManagementService.resume()))
    }
}
