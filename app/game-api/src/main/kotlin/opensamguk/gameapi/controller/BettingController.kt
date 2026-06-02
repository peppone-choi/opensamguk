package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.BettingItemResponse
import opensamguk.infra.read.BettingRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/bettings")
class BettingController(
    private val bettingRepository: BettingRepository,
) {

    @GetMapping("/{bettingId}/bets")
    fun bets(@PathVariable bettingId: Int): ResponseEntity<List<BettingItemResponse>> {
        val bets = bettingRepository.findByBettingId(bettingId)
            .map { it.toResponse() }
        return ResponseEntity.ok(bets)
    }

    @GetMapping("/general/{generalId}")
    fun byGeneral(@PathVariable generalId: Int): ResponseEntity<List<BettingItemResponse>> {
        val bets = bettingRepository.findByGeneralId(generalId)
            .map { it.toResponse() }
        return ResponseEntity.ok(bets)
    }

    private fun opensamguk.infra.entity.NgBettingEntity.toResponse() = BettingItemResponse(
        id = id,
        bettingId = bettingId,
        generalId = generalId,
        userId = userId,
        bettingType = bettingType,
        amount = amount,
    )
}
