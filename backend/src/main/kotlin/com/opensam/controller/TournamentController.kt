package com.opensam.controller

import com.opensam.dto.PlaceBetRequest
import com.opensam.dto.BettingInfoResponse
import com.opensam.dto.TournamentInfoResponse
import com.opensam.dto.TournamentRegisterRequest
import com.opensam.service.TournamentService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class TournamentController(
    private val tournamentService: TournamentService,
) {
    @GetMapping("/worlds/{worldId}/tournament")
    fun getTournament(@PathVariable worldId: Long): ResponseEntity<TournamentInfoResponse> {
        val result = tournamentService.getTournament(worldId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/tournament/register")
    fun register(
        @PathVariable worldId: Long,
        @RequestBody request: TournamentRegisterRequest,
    ): ResponseEntity<Map<String, Any>> {
        val result = tournamentService.register(worldId, request.generalId)
            ?: return ResponseEntity.notFound().build()
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/worlds/{worldId}/betting")
    fun getBetting(@PathVariable worldId: Long): ResponseEntity<BettingInfoResponse> {
        val result = tournamentService.getBetting(worldId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/betting")
    fun placeBet(
        @PathVariable worldId: Long,
        @RequestBody request: PlaceBetRequest,
    ): ResponseEntity<Map<String, Any>> {
        val result = tournamentService.placeBet(worldId, request.generalId, request.targetId, request.amount)
            ?: return ResponseEntity.notFound().build()
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }
}
