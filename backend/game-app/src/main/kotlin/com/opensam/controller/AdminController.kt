package com.opensam.controller

import com.opensam.dto.AdminDashboard
import com.opensam.dto.AdminGeneralAction
import com.opensam.dto.AdminGeneralSummary
import com.opensam.dto.AdminUserSummary
import com.opensam.dto.AdminUserAction
import com.opensam.dto.NationStatistic
import com.opensam.dto.TimeControlRequest
import com.opensam.service.AdminAuthorizationService
import com.opensam.service.AdminService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val adminService: AdminService,
    private val adminAuthorizationService: AdminAuthorizationService,
) {
    private companion object {
        const val PERMISSION_OPEN_CLOSE = "openClose"
        const val PERMISSION_BLOCK_GENERAL = "blockGeneral"
    }

    @GetMapping("/dashboard")
    fun getDashboard(@RequestParam(required = false) worldId: Long?): ResponseEntity<AdminDashboard> {
        return try {
            val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            val resolvedWorldId = adminAuthorizationService.resolveWorldIdOrThrow(loginId, worldId, PERMISSION_OPEN_CLOSE)
            ResponseEntity.ok(adminService.getDashboard(resolvedWorldId))
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @PatchMapping("/settings")
    fun updateSettings(
        @RequestParam(required = false) worldId: Long?,
        @RequestBody settings: Map<String, Any>,
    ): ResponseEntity<Void> {
        return try {
            val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            val resolvedWorldId = adminAuthorizationService.resolveWorldIdOrThrow(loginId, worldId, PERMISSION_OPEN_CLOSE)
            if (!adminService.updateSettings(resolvedWorldId, settings)) return ResponseEntity.notFound().build()
            ResponseEntity.ok().build()
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @GetMapping("/generals")
    fun listAllGenerals(@RequestParam(required = false) worldId: Long?): ResponseEntity<List<AdminGeneralSummary>> {
        return try {
            val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            val resolvedWorldId = adminAuthorizationService.resolveWorldIdOrThrow(loginId, worldId, PERMISSION_OPEN_CLOSE)
            ResponseEntity.ok(adminService.listAllGenerals(resolvedWorldId))
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @PostMapping("/generals/{id}/action")
    fun generalAction(
        @PathVariable id: Long,
        @RequestParam(required = false) worldId: Long?,
        @RequestBody action: AdminGeneralAction,
    ): ResponseEntity<Void> {
        return try {
            val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            val resolvedWorldId = adminAuthorizationService.resolveWorldIdOrThrow(loginId, worldId, PERMISSION_BLOCK_GENERAL)
            if (!adminService.generalAction(resolvedWorldId, id, action.type)) return ResponseEntity.notFound().build()
            ResponseEntity.ok().build()
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @GetMapping("/statistics")
    fun getStatistics(@RequestParam(required = false) worldId: Long?): ResponseEntity<List<NationStatistic>> {
        return try {
            val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            val resolvedWorldId = adminAuthorizationService.resolveWorldIdOrThrow(loginId, worldId, PERMISSION_OPEN_CLOSE)
            ResponseEntity.ok(adminService.getStatistics(resolvedWorldId))
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @GetMapping("/generals/{id}/logs")
    fun getGeneralLogs(
        @PathVariable id: Long,
        @RequestParam(required = false) worldId: Long?,
    ): ResponseEntity<List<Any>> {
        return try {
            val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            val resolvedWorldId = adminAuthorizationService.resolveWorldIdOrThrow(loginId, worldId, PERMISSION_OPEN_CLOSE)
            ResponseEntity.ok(adminService.getGeneralLogs(resolvedWorldId, id))
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @GetMapping("/diplomacy")
    fun getDiplomacyMatrix(@RequestParam(required = false) worldId: Long?): ResponseEntity<List<Any>> {
        return try {
            val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            val resolvedWorldId = adminAuthorizationService.resolveWorldIdOrThrow(loginId, worldId, PERMISSION_OPEN_CLOSE)
            ResponseEntity.ok(adminService.getDiplomacyMatrix(resolvedWorldId))
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @PostMapping("/time-control")
    fun timeControl(
        @RequestParam(required = false) worldId: Long?,
        @RequestBody request: TimeControlRequest,
    ): ResponseEntity<Void> {
        return try {
            val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            val resolvedWorldId = adminAuthorizationService.resolveWorldIdOrThrow(loginId, worldId, PERMISSION_OPEN_CLOSE)
            if (!adminService.timeControl(resolvedWorldId, request.year, request.month, request.locked)) {
                return ResponseEntity.notFound().build()
            }
            ResponseEntity.ok().build()
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @GetMapping("/users")
    fun listUsers(): ResponseEntity<List<AdminUserSummary>> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        try {
            adminAuthorizationService.requireGlobalAdmin(loginId)
        } catch (_: AccessDeniedException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        return ResponseEntity.ok(adminService.listUsers())
    }

    @PostMapping("/users/{id}/action")
    fun userAction(
        @PathVariable id: Long,
        @RequestBody action: AdminUserAction,
    ): ResponseEntity<Void> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        try {
            adminAuthorizationService.requireGlobalAdmin(loginId)
        } catch (_: AccessDeniedException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        if (!adminService.userAction(loginId, id, action)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return ResponseEntity.ok().build()
    }

    @PostMapping("/write-log")
    fun writeLog(
        @RequestParam(required = false) worldId: Long?,
        @RequestBody body: Map<String, String>,
    ): ResponseEntity<Void> {
        return try {
            val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            val resolvedWorldId = adminAuthorizationService.resolveWorldIdOrThrow(loginId, worldId, PERMISSION_OPEN_CLOSE)
            val message = body["message"] ?: return ResponseEntity.badRequest().build()
            if (!adminService.writeLog(resolvedWorldId, message)) return ResponseEntity.notFound().build()
            ResponseEntity.ok().build()
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @PostMapping("/generals/bulk-action")
    fun bulkGeneralAction(
        @RequestParam(required = false) worldId: Long?,
        @RequestBody body: Map<String, Any>,
    ): ResponseEntity<Void> {
        return try {
            val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            val resolvedWorldId = adminAuthorizationService.resolveWorldIdOrThrow(loginId, worldId, PERMISSION_BLOCK_GENERAL)
            @Suppress("UNCHECKED_CAST")
            val ids = (body["ids"] as? List<Number>)?.map { it.toLong() } ?: return ResponseEntity.badRequest().build()
            val type = body["type"] as? String ?: return ResponseEntity.badRequest().build()
            for (id in ids) {
                adminService.generalAction(resolvedWorldId, id, type)
            }
            ResponseEntity.ok().build()
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    private fun currentLoginId(): String? {
        return SecurityContextHolder.getContext().authentication?.name
    }
}
