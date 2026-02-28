package com.opensam.controller

import com.opensam.dto.DiplomacyDto
import com.opensam.engine.DiplomacyService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

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

    data class DiplomacyRespondRequest(
        val messageId: Long,
        val action: String,
        val accept: Boolean,
    )

    @PostMapping("/respond")
    fun respond(
        @PathVariable worldId: Long,
        @RequestBody request: DiplomacyRespondRequest,
    ): ResponseEntity<Void> {
        return try {
            if (request.accept) {
                diplomacyService.acceptDiplomaticMessage(worldId, request.messageId)
            } else {
                diplomacyService.rejectDiplomaticMessage(worldId, request.messageId)
            }
            ResponseEntity.ok().build()
        } catch (_: Exception) {
            ResponseEntity.badRequest().build()
        }
    }
}
