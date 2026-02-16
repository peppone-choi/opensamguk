package com.opensam.controller

import com.opensam.entity.Message
import com.opensam.service.HistoryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class HistoryController(
    private val historyService: HistoryService,
) {
    @GetMapping("/worlds/{worldId}/history")
    fun getWorldHistory(@PathVariable worldId: Long): ResponseEntity<List<Message>> {
        return ResponseEntity.ok(historyService.getWorldHistory(worldId))
    }

    @GetMapping("/worlds/{worldId}/records")
    fun getWorldRecords(@PathVariable worldId: Long): ResponseEntity<List<Message>> {
        return ResponseEntity.ok(historyService.getWorldRecords(worldId))
    }

    @GetMapping("/generals/{generalId}/records")
    fun getGeneralRecords(@PathVariable generalId: Long): ResponseEntity<List<Message>> {
        return ResponseEntity.ok(historyService.getGeneralRecords(generalId))
    }
}
