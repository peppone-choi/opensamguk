package com.opensam.controller

import com.opensam.dto.CreateTroopRequest
import com.opensam.dto.RenameTroopRequest
import com.opensam.dto.TroopActionRequest
import com.opensam.dto.TroopResponse
import com.opensam.dto.TroopWithMembers
import com.opensam.service.TroopService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class TroopController(
    private val troopService: TroopService,
) {
    @GetMapping("/nations/{nationId}/troops")
    fun listByNation(@PathVariable nationId: Long): ResponseEntity<List<TroopWithMembers>> {
        return ResponseEntity.ok(troopService.listByNation(nationId))
    }

    @PostMapping("/troops")
    fun create(@RequestBody request: CreateTroopRequest): ResponseEntity<TroopResponse> {
        val troop = troopService.create(request.worldId, request.leaderGeneralId, request.nationId, request.name)
        return ResponseEntity.status(HttpStatus.CREATED).body(TroopResponse.from(troop))
    }

    @PostMapping("/troops/{id}/join")
    fun join(@PathVariable id: Long, @RequestBody request: TroopActionRequest): ResponseEntity<Void> {
        if (!troopService.join(id, request.generalId)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }

    @PostMapping("/troops/{id}/exit")
    fun exit(@PathVariable id: Long, @RequestBody request: TroopActionRequest): ResponseEntity<Void> {
        if (!troopService.exit(request.generalId)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }

    @PostMapping("/troops/{id}/kick")
    fun kick(@PathVariable id: Long, @RequestBody request: TroopActionRequest): ResponseEntity<Void> {
        if (!troopService.exit(request.generalId)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }

    @PatchMapping("/troops/{id}")
    fun rename(@PathVariable id: Long, @RequestBody request: RenameTroopRequest): ResponseEntity<TroopResponse> {
        val troop = troopService.rename(id, request.name)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(TroopResponse.from(troop))
    }

    @DeleteMapping("/troops/{id}")
    fun disband(@PathVariable id: Long): ResponseEntity<Void> {
        if (!troopService.disband(id)) return ResponseEntity.notFound().build()
        return ResponseEntity.noContent().build()
    }
}
