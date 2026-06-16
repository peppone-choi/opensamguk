package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.AdminGameSettingsPatchRequest
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.security.GameApiJwtVerifier
import opensamguk.infra.entity.GameKvEntity
import opensamguk.infra.read.GameKvRepository
import opensamguk.logic.util.jsonEncode
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
    private val gameKv: GameKvRepository,
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
        val restartRequiredKeys = mutableSetOf<String>()
        for ((key, raw) in body.values) {
            val result = validateConfigValue(key, raw)
                ?: return ResponseEntity.badRequest()
                    .body(mapOf("result" to false, "reason" to "invalid value for $key: $raw"))
            validated[key] = result
            if (key == "turnterm") restartRequiredKeys += key
        }

        val nextConfig = LinkedHashMap(entity.config)
        for ((key, value) in validated) {
            when (key) {
                "msg" -> writeGameEnvMsg(value as String)
                "turnterm" -> {
                    val minutes = value as Int
                    nextConfig[key] = minutes
                    entity.tickSeconds = minutes * 60
                }
                else -> nextConfig[key] = value
            }
        }
        entity.config = nextConfig
        world.save(entity)

        return ResponseEntity.ok(
            mapOf(
                "result" to true,
                "updated" to validated.keys,
                "restartRequired" to restartRequiredKeys.isNotEmpty(),
            ),
        )
    }

    private fun writeGameEnvMsg(msg: String) {
        val trimmed = msg.trim()
        val existing = gameKv.findByTable("game_env")
            .firstOrNull { it.namespace == "global" && it.key == "msg" }
        if (existing != null) {
            existing.value = jsonEncode(trimmed)
            gameKv.save(existing)
        } else {
            gameKv.save(
                GameKvEntity(
                    table = "game_env",
                    namespace = "global",
                    key = "msg",
                    value = jsonEncode(trimmed),
                ),
            )
        }
    }

    /** 허용된 config / game_env 키만 검증·정규화. */
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
            "maxgeneral" -> {
                val v = (raw as? Number)?.toInt() ?: (raw as? String)?.toIntOrNull()
                if (v != null && v > 0 && v <= 9999) v else null
            }
            "maxnation" -> {
                val v = (raw as? Number)?.toInt() ?: (raw as? String)?.toIntOrNull()
                if (v != null && v > 0 && v <= 999) v else null
            }
            "startyear" -> {
                val v = (raw as? Number)?.toInt() ?: (raw as? String)?.toIntOrNull()
                if (v != null && v > 0 && v <= 9999) v else null
            }
            "starttime" -> {
                val v = raw as? String ?: return null
                if (v.matches(STARTTIME_REGEX)) v else null
            }
            "turnterm" -> {
                val v = (raw as? Number)?.toInt() ?: (raw as? String)?.toIntOrNull()
                if (v in TURN_OPTIONS) v else null
            }
            "msg" -> {
                val v = raw as? String ?: return null
                if (v.length <= MSG_MAX_LENGTH) v else null
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
        val STARTTIME_REGEX = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$""")
        val TURN_OPTIONS = setOf(1, 2, 5, 10, 20, 30, 60, 120)
        const val MSG_MAX_LENGTH = 500
    }
}
