package com.opensam.controller

import com.opensam.dto.DiplomacyDto
import com.opensam.engine.DiplomacyService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/worlds/{worldId}/diplomacy")
class DiplomacyController(
    private val diplomacyService: DiplomacyService,
) {
    @GetMapping
    fun getRelations(@PathVariable worldId: Long): ResponseEntity<List<DiplomacyDto>> {
        return ResponseEntity.ok(diplomacyService.getRelations(worldId).map(DiplomacyDto::from))
    }

    @GetMapping("/nation/{nationId}")
    fun getRelationsForNation(
        @PathVariable worldId: Long,
        @PathVariable nationId: Long,
    ): ResponseEntity<List<DiplomacyDto>> {
        return ResponseEntity.ok(diplomacyService.getRelationsForNation(worldId, nationId).map(DiplomacyDto::from))
    }
}
