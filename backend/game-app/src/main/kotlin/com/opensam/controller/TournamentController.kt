package com.opensam.controller

import com.opensam.dto.PlaceBetRequest
import com.opensam.dto.BettingInfoResponse
import com.opensam.dto.CreateTournamentRequest
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
    @PostMapping("/worlds/{worldId}/tournament")
    fun createTournament(
        @PathVariable worldId: Long,
        @RequestBody request: CreateTournamentRequest,
    ): ResponseEntity<Map<String, Any>> {
        val result = tournamentService.createTournament(worldId, request.type)
            ?: return ResponseEntity.notFound().build()
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

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
        val result = tournamentService.registerParticipant(worldId, request.generalId)
            ?: return ResponseEntity.notFound().build()
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/tournament/start")
    fun startTournament(@PathVariable worldId: Long): ResponseEntity<Map<String, Any>> {
        val result = tournamentService.startTournament(worldId)
            ?: return ResponseEntity.notFound().build()
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/tournament/advance")
    fun advanceRound(@PathVariable worldId: Long): ResponseEntity<Map<String, Any>> {
        val result = tournamentService.advanceRound(worldId)
            ?: return ResponseEntity.notFound().build()
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/tournament/finalize")
    fun finalizeTournament(@PathVariable worldId: Long): ResponseEntity<Map<String, Any>> {
        val result = tournamentService.finalizeTournament(worldId)
            ?: return ResponseEntity.notFound().build()
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/tournament/message")
    fun sendTournamentMessage(
        @PathVariable worldId: Long,
        @RequestBody request: Map<String, String>,
    ): ResponseEntity<Map<String, Any>> {
        val message = request["message"] ?: return ResponseEntity.badRequest().body(mapOf("error" to "메시지가 필요합니다"))
        val result = tournamentService.sendTournamentMessage(worldId, message)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }

    @GetMapping("/worlds/{worldId}/betting/history")
    fun getBettingHistory(@PathVariable worldId: Long): ResponseEntity<List<Map<String, Any?>>> {
        return ResponseEntity.ok(tournamentService.getBettingHistory(worldId))
    }

    @GetMapping("/worlds/{worldId}/betting/{yearMonth}")
    fun getBettingEvent(
        @PathVariable worldId: Long,
        @PathVariable yearMonth: String,
    ): ResponseEntity<BettingInfoResponse?> {
        val result = tournamentService.getBettingEvent(worldId, yearMonth)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/betting/gate")
    fun toggleBettingGate(
        @PathVariable worldId: Long,
        @RequestBody request: Map<String, Boolean>,
    ): ResponseEntity<Map<String, Any>> {
        val open = request["open"] ?: return ResponseEntity.badRequest().body(mapOf("error" to "open 필드 필요"))
        val result = tournamentService.toggleBettingGate(worldId, open)
            ?: return ResponseEntity.notFound().build()
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
