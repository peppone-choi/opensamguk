package com.opensam.controller

import com.opensam.engine.TurnDaemon
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/turns")
class TurnController(
    private val turnDaemon: TurnDaemon,
) {
    @GetMapping("/status")
    fun getStatus(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("state" to turnDaemon.getStatus().name))
    }

    @PostMapping("/run")
    fun manualRun(): ResponseEntity<Map<String, String>> {
        turnDaemon.manualRun()
        return ResponseEntity.ok(mapOf("result" to "triggered"))
    }

    @PostMapping("/pause")
    fun pause(): ResponseEntity<Map<String, String>> {
        turnDaemon.pause()
        return ResponseEntity.ok(mapOf("state" to turnDaemon.getStatus().name))
    }

    @PostMapping("/resume")
    fun resume(): ResponseEntity<Map<String, String>> {
        turnDaemon.resume()
        return ResponseEntity.ok(mapOf("state" to turnDaemon.getStatus().name))
    }
}
