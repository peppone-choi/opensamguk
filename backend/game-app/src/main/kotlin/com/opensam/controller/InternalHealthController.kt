package com.opensam.controller

import com.opensam.engine.TurnDaemon
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal")
class InternalHealthController(
    private val turnDaemon: TurnDaemon,
) {
    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf(
            "status" to "ok",
            "service" to "game",
            "turnState" to turnDaemon.getStatus().name,
        ))
    }

    @PostMapping("/turn/pause")
    fun pauseTurn(): ResponseEntity<Map<String, String>> {
        turnDaemon.pause()
        return ResponseEntity.ok(mapOf("state" to turnDaemon.getStatus().name))
    }

    @PostMapping("/turn/resume")
    fun resumeTurn(): ResponseEntity<Map<String, String>> {
        turnDaemon.resume()
        return ResponseEntity.ok(mapOf("state" to turnDaemon.getStatus().name))
    }
}
