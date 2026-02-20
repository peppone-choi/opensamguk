package com.opensam.controller

import com.opensam.service.AdminAuthorizationService
import com.opensam.service.TurnManagementService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/turns")
class TurnController(
    private val turnManagementService: TurnManagementService,
    private val adminAuthorizationService: AdminAuthorizationService,
) {
    private companion object {
        const val PERMISSION_OPEN_CLOSE = "openClose"
    }

    private fun currentLoginId(): String? = SecurityContextHolder.getContext().authentication?.name

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("state" to turnManagementService.getStatus()))
    }

    @PostMapping("/run")
    fun manualRun(): ResponseEntity<Map<String, String>> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        try {
            adminAuthorizationService.requireAnyPermission(
                loginId,
                listOf(PERMISSION_OPEN_CLOSE, "admin.profiles.manage"),
            )
        } catch (_: AccessDeniedException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        return ResponseEntity.ok(mapOf("result" to turnManagementService.manualRun()))
    }

    @PostMapping("/pause")
    fun pause(): ResponseEntity<Map<String, String>> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        try {
            adminAuthorizationService.requireAnyPermission(
                loginId,
                listOf(PERMISSION_OPEN_CLOSE, "admin.profiles.manage"),
            )
        } catch (_: AccessDeniedException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        return ResponseEntity.ok(mapOf("state" to turnManagementService.pause()))
    }

    @PostMapping("/resume")
    fun resume(): ResponseEntity<Map<String, String>> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        try {
            adminAuthorizationService.requireAnyPermission(
                loginId,
                listOf(
                    PERMISSION_OPEN_CLOSE,
                    "admin.profiles.manage",
                    "admin.resume.when-stopped",
                ),
            )
        } catch (_: AccessDeniedException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        return ResponseEntity.ok(mapOf("state" to turnManagementService.resume()))
    }
}
