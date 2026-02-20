package com.opensam.controller

import com.opensam.dto.NpcTokenResponse
import com.opensam.dto.RefreshNpcTokenRequest
import com.opensam.dto.SelectNpcResult
import com.opensam.dto.SelectNpcWithTokenRequest
import com.opensam.service.GeneralService
import com.opensam.service.SelectNpcTokenService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class NpcSelectionController(
    private val generalService: GeneralService,
    private val selectNpcTokenService: SelectNpcTokenService,
) {
    @PostMapping("/worlds/{worldId}/npc-token")
    fun generateToken(@PathVariable worldId: Long): ResponseEntity<Any> {
        val userId = currentUserId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            ResponseEntity.ok(selectNpcTokenService.generateToken(worldId, userId))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(errorResponse(e.message ?: "잘못된 요청입니다."))
        } catch (e: IllegalStateException) {
            ResponseEntity.badRequest().body(errorResponse(e.message ?: "요청을 처리할 수 없습니다."))
        }
    }

    @PostMapping("/worlds/{worldId}/npc-token/refresh")
    fun refreshToken(
        @PathVariable worldId: Long,
        @RequestBody request: RefreshNpcTokenRequest,
    ): ResponseEntity<Any> {
        val userId = currentUserId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            ResponseEntity.ok(selectNpcTokenService.refreshToken(worldId, userId, request.nonce, request.keepIds))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(errorResponse(e.message ?: "잘못된 요청입니다."))
        } catch (e: IllegalStateException) {
            ResponseEntity.badRequest().body(errorResponse(e.message ?: "요청을 처리할 수 없습니다."))
        }
    }

    @PostMapping("/worlds/{worldId}/npc-select")
    fun selectNpc(
        @PathVariable worldId: Long,
        @RequestBody request: SelectNpcWithTokenRequest,
    ): ResponseEntity<Any> {
        val userId = currentUserId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            ResponseEntity.ok(selectNpcTokenService.selectNpc(worldId, userId, request.nonce, request.generalId))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(errorResponse(e.message ?: "잘못된 요청입니다."))
        } catch (e: IllegalStateException) {
            ResponseEntity.badRequest().body(errorResponse(e.message ?: "요청을 처리할 수 없습니다."))
        }
    }

    private fun errorResponse(message: String): Map<String, String> {
        return mapOf("message" to message)
    }

    private fun currentUserId(): Long? {
        val loginId = SecurityContextHolder.getContext().authentication?.name ?: return null
        return generalService.getCurrentUserId(loginId)
    }
}
