package opensamguk.gameapi.controller

import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.security.GameApiJwtVerifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin write API — server management mutations that do NOT touch game-state
 * (no ChangeRecorder / one-daemon-write rule violation).
 *
 * ADMIN gate: same pattern as AdminReadController.requireAdmin —
 * gateway access token `role` claim must be `"ADMIN"`.
 */
@RestController
@RequestMapping("/api/admin")
class AdminWriteController(
    private val verifier: GameApiJwtVerifier,
    private val world: WorldStateReadRepository,
) {

    @PostMapping("/server-status")
    fun updateServerStatus(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestBody body: ServerStatusUpdateRequest,
    ): ResponseEntity<Any> {
        val gate = requireAdmin(authorization)
        if (gate != null) return gate

        val entity = world.findById(1).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("result" to false, "reason" to "world_state not found"))

        entity.status = body.status
        world.save(entity)

        return ResponseEntity.ok(mapOf("result" to true, "status" to body.status))
    }

    data class ServerStatusUpdateRequest(
        val status: String,
    )

    // ── ADMIN gate (mirrors AdminReadController) ────────────────────────────

    private fun requireAdmin(authorization: String?): ResponseEntity<Any>? {
        val token = bearer(authorization) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (!verifier.isValid(token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (verifier.getRole(token) != ADMIN_ROLE) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return null
    }

    private fun bearer(authorization: String?): String? {
        return authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
    }

    companion object {
        private const val ADMIN_ROLE = "ADMIN"
    }
}
