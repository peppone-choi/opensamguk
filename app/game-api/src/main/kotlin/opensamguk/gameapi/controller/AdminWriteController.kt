package opensamguk.gameapi.controller

import opensamguk.gameapi.admin.AdminGeneralModerationService
import opensamguk.common.wire.AdminWorldSetting
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.dto.AdminGameSettingsPatchRequest
import opensamguk.gameapi.dto.AdminGeneralModerationActionRequest
import opensamguk.gameapi.dto.AdminGeneralModerationActionResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.reserve.CommandReserveService
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
 * Admin write API. Every game-state and server-setting mutation is queued to the daemon command
 * stream; this controller only authenticates, validates, and publishes intake commands.
 *
 * ADMIN gate: same pattern as AdminReadController.requireAdmin —
 * gateway access token `role` claim must be `"ADMIN"`.
 */
@RestController
@RequestMapping("/api/admin")
class AdminWriteController(
    private val verifier: GameApiJwtVerifier,
    private val commands: CommandReserveService,
    private val generalResolver: GeneralResolver,
    private val generalModeration: AdminGeneralModerationService,
) {

    @PostMapping("/server-status")
    fun updateServerStatus(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestBody body: ServerStatusUpdateRequest,
    ): ResponseEntity<Any> {
        val gate = requireAdmin(authorization)
        if (gate != null) return gate

        if (body.status !in SERVER_STATUSES) {
            return ResponseEntity.badRequest().body(mapOf("result" to false, "reason" to "invalid status"))
        }
        commands.publishImmediate(TurnDaemonCommand.AdminWorldSettings(status = body.status))
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(mapOf("result" to true, "status" to body.status))
    }

    @PostMapping("/general-moderation")
    fun generalModerationAction(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestBody body: AdminGeneralModerationActionRequest,
    ): ResponseEntity<Any> {
        val gate = requireAdmin(authorization)
        if (gate != null) return gate

        val token = bearer(authorization)
        val actorGeneralId = token
            ?.let { verifier.getUserId(it) }
            ?.let { generalResolver.resolveGeneralId(it) }
        return try {
            val result = generalModeration.apply(body.action, body.generalIds, body.message, actorGeneralId)
            ResponseEntity.status(HttpStatus.ACCEPTED).body(
                AdminGeneralModerationActionResponse(
                    result = true,
                    action = result.action,
                    affected = result.affected,
                ),
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("result" to false, "reason" to (e.message ?: "잘못된 요청입니다.")))
        }
    }

    @PatchMapping("/game-settings")
    fun patchGameSettings(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestBody body: AdminGameSettingsPatchRequest,
    ): ResponseEntity<Any> {
        val gate = requireAdmin(authorization)
        if (gate != null) return gate

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

        val settings = validated.map { (key, value) ->
            when (value) {
                is Int -> AdminWorldSetting(key = key, intValue = value)
                is String -> AdminWorldSetting(key = key, stringValue = if (key == "msg") value.trim() else value)
                else -> error("validated admin setting has unsupported type: ${value::class}")
            }
        }
        commands.publishImmediate(TurnDaemonCommand.AdminWorldSettings(settings = settings))

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            mapOf(
                "result" to true,
                "updated" to validated.keys,
                "restartRequired" to restartRequiredKeys.isNotEmpty(),
            ),
        )
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
        private val SERVER_STATUSES = setOf("CLOSED", "PRE_OPEN", "OPEN")
        val STARTTIME_REGEX = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$""")
        val TURN_OPTIONS = setOf(1, 2, 5, 10, 20, 30, 60, 120)
        const val MSG_MAX_LENGTH = 500
    }
}
