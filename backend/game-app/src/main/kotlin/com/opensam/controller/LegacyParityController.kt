package com.opensam.controller

import com.opensam.service.*
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

/**
 * Controller covering legacy PHP endpoints that were missing from the backend.
 * These map to: j_adjust_icon, j_general_log_old, j_general_set_permission,
 * j_export_simulator_object, j_map_recent, j_raise_event, j_autoreset.
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
    private val autoResetService: AutoResetService,
    private val mapRecentService: MapRecentService,
    private val adminEventService: AdminEventService,
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

    // ── j_map_recent.php ──
    @GetMapping("/worlds/{worldId}/map-recent")
    fun getMapRecent(
        @PathVariable worldId: Long,
        @RequestHeader(value = "If-None-Match", required = false) ifNoneMatch: String?,
    ): ResponseEntity<Any> {
        val (cacheEntry, notModified) = mapRecentService.getMapRecent(worldId, ifNoneMatch)

        if (notModified) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .header(HttpHeaders.ETAG, "\"${cacheEntry.etag}\"")
                .build()
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.ETAG, "\"${cacheEntry.etag}\"")
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=600")
            .body(cacheEntry.data)
    }

    // ── j_raise_event.php ──
    @PostMapping("/admin/raise-event")
    fun raiseEvent(@RequestBody request: RaiseEventRequest): ResponseEntity<Any> {
        val loginId = currentLoginId()
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("result" to false, "reason" to "Unauthorized"))

        val result = adminEventService.raiseEvent(
            loginId = loginId,
            eventName = request.event,
            eventArgs = request.args,
            worldId = request.worldId,
        )

        return if (result.result) {
            ResponseEntity.ok(mapOf(
                "result" to true,
                "reason" to result.reason,
                "info" to result.info,
            ))
        } else {
            ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("result" to false, "reason" to result.reason))
        }
    }

    // ── j_autoreset.php ──
    @PostMapping("/worlds/{worldId}/auto-reset-check")
    fun autoResetCheck(@PathVariable worldId: Long): ResponseEntity<Any> {
        val result = autoResetService.checkAutoReset(worldId)
        return ResponseEntity.ok(mapOf(
            "result" to result.result,
            "affected" to result.affected,
            "status" to result.status,
            "info" to result.info,
        ))
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
    val worldId: Long? = null,
)
