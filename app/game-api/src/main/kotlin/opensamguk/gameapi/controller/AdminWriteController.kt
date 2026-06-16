package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.AdminGameSettingsPatchRequest
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.security.GameApiJwtVerifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
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

    @PatchMapping("/game-settings")
    fun patchGameSettings(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestBody body: AdminGameSettingsPatchRequest,
    ): ResponseEntity<Any> {
        val gate = requireAdmin(authorization)
        if (gate != null) return gate

        val entity = world.findById(1).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("result" to false, "reason" to "world_state not found"))

        if (body.values.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(mapOf("result" to false, "reason" to "values is empty"))
        }

        val validated = mutableMapOf<String, Any>()
        for ((key, raw) in body.values) {
            val result = validateConfigValue(key, raw)
                ?: return ResponseEntity.badRequest()
                    .body(mapOf("result" to false, "reason" to "invalid value for $key: $raw"))
            validated[key] = result
        }

        val nextConfig = LinkedHashMap(entity.config)
        for ((key, value) in validated) {
            nextConfig[key] = value
        }
        entity.config = nextConfig
        world.save(entity)

        return ResponseEntity.ok(
            mapOf(
                "result" to true,
                "updated" to validated.keys,
            ),
        )
    }

    /** 허용된 config 키만 검증·정규화. 현재는 입장 게이팅 2개만 라이브 수정 대상. */
    private fun validateConfigValue(key: String, raw: Any?): Any? {
        return when (key) {
            "npcmode" -> {
                val v = (raw as? Number)?.toInt() ?: (raw as? String)?.toIntOrNull()
                if (v in 0..2) v else null
            }
            "block_general_create" -> {
                val v = (raw as? Number)?.toInt() ?: (raw as? String)?.toIntOrNull()
                if (v in 0..2) v else null
            }
            else -> null
        }
    }

    // ── ADMIN gate (mirrors AdminReadController) ────────────────────────────

    data class ServerStatusUpdateRequest(
        val status: String,
    )

    private fun requireAdmin(authorization: String?): ResponseEntity<Any>? {
        val token = bearer(authorization) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (!verifier.isValid(token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (verifier.getRole(token) != ADMIN_ROLE) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return null
    }

    private fun bearer(authorization: String?): String? {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null
        return authorization.substring(7).ifBlank { null }
    }

    companion object {
        private const val ADMIN_ROLE = "ADMIN"
    }
}
