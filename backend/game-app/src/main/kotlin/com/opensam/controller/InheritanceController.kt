package com.opensam.controller

import com.opensam.dto.AuctionUniqueRequest
import com.opensam.dto.BuyInheritBuffRequest
import com.opensam.dto.CheckOwnerRequest
import com.opensam.dto.CheckOwnerResponse
import com.opensam.dto.InheritanceActionResult
import com.opensam.dto.InheritanceInfo
import com.opensam.dto.InheritanceLogEntry
import com.opensam.dto.ResetStatsRequest
import com.opensam.dto.SetInheritCityRequest
import com.opensam.dto.SetInheritSpecialRequest
import com.opensam.service.InheritanceService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class InheritanceController(
    private val inheritanceService: InheritanceService,
) {
    private fun getLoginId(): String? = SecurityContextHolder.getContext().authentication?.name

    @GetMapping("/worlds/{worldId}/inheritance")
    fun getInheritance(@PathVariable worldId: Long): ResponseEntity<InheritanceInfo> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val info = inheritanceService.getInheritance(worldId, loginId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(info)
    }


    @PostMapping("/worlds/{worldId}/inheritance/buy")
    fun buyInherit(
        @PathVariable worldId: Long,
        @RequestBody request: Map<String, String>,
    ): ResponseEntity<InheritanceActionResult> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val buffCode = request["buffCode"] ?: return ResponseEntity.badRequest().build()
        val result = inheritanceService.buyInheritBuff(worldId, loginId, BuyInheritBuffRequest(type = buffCode, level = 1))
            ?: return ResponseEntity.notFound().build()
        if (result.error != null) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/inheritance/special")
    fun setInheritSpecial(
        @PathVariable worldId: Long,
        @RequestBody request: SetInheritSpecialRequest,
    ): ResponseEntity<InheritanceActionResult> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val result = inheritanceService.setInheritSpecial(worldId, loginId, request.specialCode)
            ?: return ResponseEntity.notFound().build()
        if (result.error != null) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/inheritance/city")
    fun setInheritCity(
        @PathVariable worldId: Long,
        @RequestBody request: SetInheritCityRequest,
    ): ResponseEntity<InheritanceActionResult> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val result = inheritanceService.setInheritCity(worldId, loginId, request.cityId)
            ?: return ResponseEntity.notFound().build()
        if (result.error != null) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/inheritance/reset-turn")
    fun resetTurn(@PathVariable worldId: Long): ResponseEntity<InheritanceActionResult> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val result = inheritanceService.resetTurn(worldId, loginId)
            ?: return ResponseEntity.notFound().build()
        if (result.error != null) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/inheritance/random-unique")
    fun buyRandomUnique(@PathVariable worldId: Long): ResponseEntity<InheritanceActionResult> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val result = inheritanceService.buyRandomUnique(worldId, loginId)
            ?: return ResponseEntity.notFound().build()
        if (result.error != null) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/inheritance/reset-special-war")
    fun resetSpecialWar(@PathVariable worldId: Long): ResponseEntity<InheritanceActionResult> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val result = inheritanceService.resetSpecialWar(worldId, loginId)
            ?: return ResponseEntity.notFound().build()
        if (result.error != null) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/inheritance/reset-stats")
    fun resetStats(
        @PathVariable worldId: Long,
        @RequestBody request: ResetStatsRequest,
    ): ResponseEntity<InheritanceActionResult> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val result = inheritanceService.resetStats(worldId, loginId, request)
            ?: return ResponseEntity.notFound().build()
        if (result.error != null) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/inheritance/check-owner")
    fun checkOwner(
        @PathVariable worldId: Long,
        @RequestBody request: CheckOwnerRequest,
    ): ResponseEntity<CheckOwnerResponse> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val result = inheritanceService.checkOwner(worldId, loginId, request)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/inheritance/buy-buff")
    fun buyInheritBuff(
        @PathVariable worldId: Long,
        @RequestBody request: BuyInheritBuffRequest,
    ): ResponseEntity<InheritanceActionResult> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val result = inheritanceService.buyInheritBuff(worldId, loginId, request)
            ?: return ResponseEntity.notFound().build()
        if (result.error != null) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/worlds/{worldId}/inheritance/log")
    fun getMoreLog(
        @PathVariable worldId: Long,
        @RequestParam lastID: Long,
    ): ResponseEntity<Map<String, List<InheritanceLogEntry>>> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val logs = inheritanceService.getMoreLog(worldId, loginId, lastID)
        return ResponseEntity.ok(mapOf("log" to logs))
    }

    @PostMapping("/worlds/{worldId}/inheritance/auction-unique")
    fun auctionUnique(
        @PathVariable worldId: Long,
        @RequestBody request: AuctionUniqueRequest,
    ): ResponseEntity<InheritanceActionResult> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val result = inheritanceService.auctionUnique(worldId, loginId, request)
            ?: return ResponseEntity.notFound().build()
        if (result.error != null) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }
}
