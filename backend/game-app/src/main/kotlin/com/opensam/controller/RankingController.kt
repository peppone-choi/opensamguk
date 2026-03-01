package com.opensam.controller

import com.opensam.dto.BestGeneralResponse
import com.opensam.dto.MessageResponse
import com.opensam.service.RankingService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class RankingController(
    private val rankingService: RankingService,
) {
    @GetMapping("/worlds/{worldId}/best-generals")
    fun bestGenerals(
        @PathVariable worldId: Long,
        @RequestParam(defaultValue = "experience") sortBy: String,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<List<BestGeneralResponse>> {
        return ResponseEntity.ok(rankingService.bestGenerals(worldId, sortBy, limit))
    }

    @GetMapping("/worlds/{worldId}/hall-of-fame")
    fun hallOfFame(@PathVariable worldId: Long): ResponseEntity<List<MessageResponse>> {
        return ResponseEntity.ok(rankingService.hallOfFame(worldId).map { MessageResponse.from(it) })
    }

    @GetMapping("/worlds/{worldId}/hall-of-fame/options")
    fun hallOfFameOptions(@PathVariable worldId: Long): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(rankingService.hallOfFameOptions(worldId))
    }

    @GetMapping("/worlds/{worldId}/unique-item-owners")
    fun uniqueItemOwners(@PathVariable worldId: Long): ResponseEntity<List<Map<String, Any?>>> {
        return ResponseEntity.ok(rankingService.uniqueItemOwners(worldId))
    }
}
