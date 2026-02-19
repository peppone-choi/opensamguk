package com.opensam.controller

import com.opensam.dto.MessageResponse
import com.opensam.dto.YearbookSummaryResponse
import com.opensam.service.HistoryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class HistoryController(
    private val historyService: HistoryService,
) {
    @GetMapping("/worlds/{worldId}/history")
    fun getWorldHistory(
        @PathVariable worldId: Long,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) month: Int?,
    ): ResponseEntity<List<MessageResponse>> {
        val data = if (year != null && month != null) {
            historyService.getByYearMonth(worldId, year, month)
        } else {
            historyService.getWorldHistory(worldId)
        }
        return ResponseEntity.ok(data.map { MessageResponse.from(it) })
    }

    @GetMapping("/worlds/{worldId}/records")
    fun getWorldRecords(@PathVariable worldId: Long): ResponseEntity<List<MessageResponse>> {
        return ResponseEntity.ok(historyService.getWorldRecords(worldId).map { MessageResponse.from(it) })
    }

    @GetMapping("/generals/{generalId}/records")
    fun getGeneralRecords(@PathVariable generalId: Long): ResponseEntity<List<MessageResponse>> {
        return ResponseEntity.ok(historyService.getGeneralRecords(generalId).map { MessageResponse.from(it) })
    }

    @GetMapping("/worlds/{worldId}/history/yearbook")
    fun getYearbook(
        @PathVariable worldId: Long,
        @RequestParam year: Int,
    ): ResponseEntity<YearbookSummaryResponse> {
        val yearbook = historyService.getYearbook(worldId, year) ?: return ResponseEntity.notFound().build()
        val keyEvents = historyService.getYearKeyEvents(worldId, year)
        return ResponseEntity.ok(YearbookSummaryResponse.from(worldId, yearbook, keyEvents))
    }
}
