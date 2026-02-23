package com.opensam.controller

import com.opensam.service.*
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

/**
 * Controller covering legacy PHP endpoints that were missing from the backend.
 * These map to: j_adjust_icon, j_general_log_old, j_general_set_permission,
 * j_export_simulator_object, j_map_recent, j_raise_event.
 */
@RestController
@RequestMapping("/api")
class LegacyParityController(
    private val iconSyncService: IconSyncService,
    private val generalLogService: GeneralLogService,
    private val permissionService: PermissionService,
    private val simulatorExportService: SimulatorExportService,
    private val generalService: GeneralService,
    private val mapService: MapService,
) {
    // ── j_adjust_icon.php ──
    @PostMapping("/generals/me/sync-icon")
    fun syncIcon(): ResponseEntity<Any> {
        val loginId = currentLoginId() ?: return unauthorized()
        return ResponseEntity.ok(iconSyncService.syncIcon(loginId))
    }

    // ── j_general_log_old.php ──
    @GetMapping("/generals/{generalId}/logs/old")
    fun getOldLogs(
        @PathVariable generalId: Long,
        @RequestParam targetId: Long,
        @RequestParam type: String,
        @RequestParam(defaultValue = "${Long.MAX_VALUE}") to: Long,
    ): ResponseEntity<Any> {
        return ResponseEntity.ok(generalLogService.getOldLogs(generalId, targetId, type, to))
    }

    // ── j_general_set_permission.php ──
    @PostMapping("/nations/{nationId}/permissions")
    fun setPermission(
        @PathVariable nationId: Long,
        @RequestBody request: SetPermissionRequest,
    ): ResponseEntity<Any> {
        val loginId = currentLoginId() ?: return unauthorized()
        val userId = generalService.getCurrentUserId(loginId) ?: return unauthorized()
        // Find general by userId (any world – caller must be leader)
        return ResponseEntity.ok(
            permissionService.setPermission(request.requesterId, request.isAmbassador, request.generalIds)
        )
    }

    // ── j_export_simulator_object.php ──
    @GetMapping("/generals/{generalId}/simulator-export")
    fun exportSimulator(
        @PathVariable generalId: Long,
        @RequestParam targetId: Long,
    ): ResponseEntity<Any> {
        return ResponseEntity.ok(simulatorExportService.exportGeneralForSimulator(generalId, targetId))
    }

    // ── j_map_recent.php (stub) ──
    @GetMapping("/worlds/{worldId}/map-recent")
    fun getMapRecent(
        @PathVariable worldId: Long,
        @RequestParam(required = false) since: String?,
    ): ResponseEntity<Any> {
        // TODO: implement recent map changes delta
        return ResponseEntity.ok(mapOf("result" to true, "changes" to emptyList<Any>()))
    }

    // ── j_raise_event.php ──
    @PostMapping("/admin/raise-event")
    fun raiseEvent(@RequestBody request: RaiseEventRequest): ResponseEntity<Any> {
        // TODO: implement admin event raising with proper authorization
        return ResponseEntity.ok(mapOf("result" to true, "reason" to "stub - not yet implemented"))
    }

    // ── j_autoreset.php (stub) ──
    @PostMapping("/worlds/{worldId}/auto-reset-check")
    fun autoResetCheck(@PathVariable worldId: Long): ResponseEntity<Any> {
        // TODO: implement auto-reset check logic
        return ResponseEntity.ok(mapOf("result" to true, "affected" to 0, "status" to "not_yet"))
    }

    private fun currentLoginId(): String? = SecurityContextHolder.getContext().authentication?.name

    private fun unauthorized(): ResponseEntity<Any> =
        ResponseEntity.status(401).body(mapOf("result" to false, "reason" to "Unauthorized"))
}

data class SetPermissionRequest(
    val requesterId: Long,
    val isAmbassador: Boolean,
    val generalIds: List<Long>,
)

data class RaiseEventRequest(
    val event: String,
    val args: List<Any>? = null,
)
