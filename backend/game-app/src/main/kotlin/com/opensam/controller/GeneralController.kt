package com.opensam.controller

import com.opensam.dto.BuildPoolGeneralRequest
import com.opensam.dto.CreateGeneralRequest
import com.opensam.dto.FrontInfoResponse
import com.opensam.dto.GeneralResponse
import com.opensam.dto.SelectNpcRequest
import com.opensam.dto.UpdatePoolGeneralRequest
import com.opensam.service.FrontInfoService
import com.opensam.service.GeneralService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class GeneralController(
    private val generalService: GeneralService,
    private val frontInfoService: FrontInfoService,
) {
    @GetMapping("/worlds/{worldId}/front-info")
    fun getFrontInfo(
        @PathVariable worldId: Long,
        @RequestParam(required = false) lastRecordId: Long?,
        @RequestParam(required = false) lastHistoryId: Long?,
    ): ResponseEntity<FrontInfoResponse> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return ResponseEntity.ok(frontInfoService.getFrontInfo(worldId, loginId, lastRecordId, lastHistoryId))
    }

    @GetMapping("/worlds/{worldId}/generals")
    fun listByWorld(@PathVariable worldId: Long): ResponseEntity<List<GeneralResponse>> {
        return ResponseEntity.ok(generalService.listByWorld(worldId).map { GeneralResponse.from(it) })
    }

    @GetMapping("/worlds/{worldId}/generals/me")
    fun getMyGeneral(@PathVariable worldId: Long): ResponseEntity<GeneralResponse> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val general = generalService.getMyGeneral(worldId, loginId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(GeneralResponse.from(general))
    }

    @GetMapping("/generals/{id}")
    fun getById(@PathVariable id: Long): ResponseEntity<GeneralResponse> {
        val general = generalService.getById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(GeneralResponse.from(general))
    }

    @GetMapping("/nations/{nationId}/generals")
    fun listByNation(@PathVariable nationId: Long): ResponseEntity<List<GeneralResponse>> {
        return ResponseEntity.ok(generalService.listByNation(nationId).map { GeneralResponse.from(it) })
    }

    @GetMapping("/cities/{cityId}/generals")
    fun listByCity(@PathVariable cityId: Long): ResponseEntity<List<GeneralResponse>> {
        return ResponseEntity.ok(generalService.listByCity(cityId).map { GeneralResponse.from(it) })
    }

    @PostMapping("/worlds/{worldId}/generals")
    fun createGeneral(
        @PathVariable worldId: Long,
        @RequestBody request: CreateGeneralRequest,
    ): ResponseEntity<GeneralResponse> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val general = generalService.createGeneral(worldId, loginId, request)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return ResponseEntity.status(HttpStatus.CREATED).body(GeneralResponse.from(general))
    }

    @GetMapping("/worlds/{worldId}/available-npcs")
    fun listAvailableNpcs(@PathVariable worldId: Long): ResponseEntity<List<GeneralResponse>> {
        return ResponseEntity.ok(generalService.listAvailableNpcs(worldId).map { GeneralResponse.from(it) })
    }

    @PostMapping("/worlds/{worldId}/select-npc")
    fun selectNpc(
        @PathVariable worldId: Long,
        @RequestBody request: SelectNpcRequest,
    ): ResponseEntity<GeneralResponse> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val general = generalService.possessNpc(worldId, loginId, request.generalId)
            ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(GeneralResponse.from(general))
    }

    @GetMapping("/worlds/{worldId}/pool")
    fun listPool(@PathVariable worldId: Long): ResponseEntity<List<GeneralResponse>> {
        return ResponseEntity.ok(generalService.listPool(worldId).map { GeneralResponse.from(it) })
    }

    @PostMapping("/worlds/{worldId}/pool")
    fun buildPoolGeneral(
        @PathVariable worldId: Long,
        @RequestBody request: BuildPoolGeneralRequest,
    ): ResponseEntity<GeneralResponse> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val general = generalService.buildPoolGeneral(
            worldId, loginId, request.name,
            request.leadership, request.strength, request.intel, request.politics, request.charm,
        ) ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.status(HttpStatus.CREATED).body(GeneralResponse.from(general))
    }

    @PutMapping("/worlds/{worldId}/pool/{generalId}")
    fun updatePoolGeneral(
        @PathVariable worldId: Long,
        @PathVariable generalId: Long,
        @RequestBody request: UpdatePoolGeneralRequest,
    ): ResponseEntity<GeneralResponse> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val general = generalService.updatePoolGeneral(
            worldId, loginId, generalId,
            request.leadership, request.strength, request.intel, request.politics, request.charm,
        ) ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(GeneralResponse.from(general))
    }

    @PostMapping("/worlds/{worldId}/select-pool")
    fun selectFromPool(
        @PathVariable worldId: Long,
        @RequestBody request: SelectNpcRequest,
    ): ResponseEntity<GeneralResponse> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val general = generalService.selectFromPool(worldId, loginId, request.generalId)
            ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(GeneralResponse.from(general))
    }
}
