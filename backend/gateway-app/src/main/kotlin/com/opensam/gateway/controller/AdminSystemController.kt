package com.opensam.gateway.controller

import com.opensam.gateway.repository.AppUserRepository
import com.opensam.gateway.service.GatewayAdminAuthorizationService
import com.opensam.gateway.service.SystemSettingsService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api/admin")
class AdminSystemController(
    private val authorizationService: GatewayAdminAuthorizationService,
    private val systemSettingsService: SystemSettingsService,
    private val appUserRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    data class AuthFlagsResponse(val allowLogin: Boolean, val allowJoin: Boolean)
    data class AuthFlagsPatchRequest(val allowLogin: Boolean? = null, val allowJoin: Boolean? = null)

    data class ScrubRequest(val type: String)
    data class ScrubResponse(val affected: Int)

    private fun currentLoginId(): String? = SecurityContextHolder.getContext().authentication?.name

    @GetMapping("/system-flags")
    fun getSystemFlags(): ResponseEntity<AuthFlagsResponse> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            authorizationService.requireGlobalAdmin(loginId)
            val flags = systemSettingsService.getAuthFlags()
            ResponseEntity.ok(AuthFlagsResponse(flags.allowLogin, flags.allowJoin))
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @PatchMapping("/system-flags")
    fun patchSystemFlags(@RequestBody req: AuthFlagsPatchRequest): ResponseEntity<AuthFlagsResponse> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            authorizationService.requireGlobalAdmin(loginId)
            val flags = systemSettingsService.updateAuthFlags(req.allowLogin, req.allowJoin)
            ResponseEntity.ok(AuthFlagsResponse(flags.allowLogin, flags.allowJoin))
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @PostMapping("/scrub")
    fun scrub(@RequestBody req: ScrubRequest): ResponseEntity<ScrubResponse> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            authorizationService.requireGlobalAdmin(loginId)
            val affected = when (req.type) {
                // NOTE: OpenSam doesn't have legacy delete_after/icon storage; provide safe equivalents.
                "scrub_old_user" -> {
                    val threshold = OffsetDateTime.now().minusMonths(6)
                    val candidates = appUserRepository.findAll().filter { u ->
                        val last = u.lastLoginAt ?: u.createdAt
                        u.grade.toInt() < 5 && last.isBefore(threshold)
                    }
                    appUserRepository.deleteAll(candidates)
                    candidates.size
                }
                "scrub_blocked_user" -> {
                    val threshold = OffsetDateTime.now().minusMonths(12)
                    val candidates = appUserRepository.findAll().filter { u ->
                        val last = u.lastLoginAt ?: u.createdAt
                        u.grade.toInt() == 0 && last.isBefore(threshold)
                    }
                    appUserRepository.deleteAll(candidates)
                    candidates.size
                }
                else -> return ResponseEntity.badRequest().build()
            }
            ResponseEntity.ok(ScrubResponse(affected))
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @PostMapping("/users/{id}/reset-password")
    fun resetPassword(@PathVariable id: Long): ResponseEntity<Map<String, String>> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            authorizationService.requireGlobalAdmin(loginId)
            authorizationService.requireLowerGrade(loginId, id)
            val user = appUserRepository.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
            val raw = (1..8).map { ("ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random()) }.joinToString("")
            user.passwordHash = passwordEncoder.encode(raw)
            user.meta["tempPasswordIssuedAt"] = OffsetDateTime.now().toString()
            appUserRepository.save(user)
            ResponseEntity.ok(mapOf("tempPassword" to raw))
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }
}
