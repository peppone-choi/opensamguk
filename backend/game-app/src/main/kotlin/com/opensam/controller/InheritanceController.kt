package com.opensam.controller

import com.opensam.dto.BuyBuffRequest
import com.opensam.dto.InheritanceActionResult
import com.opensam.dto.InheritanceInfo
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
    fun buyBuff(
        @PathVariable worldId: Long,
        @RequestBody request: BuyBuffRequest,
    ): ResponseEntity<InheritanceActionResult> {
        val loginId = getLoginId() ?: return ResponseEntity.status(401).build()
        val result = inheritanceService.buyBuff(worldId, loginId, request.buffCode)
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
}
