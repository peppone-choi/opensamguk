package com.opensam.gateway.controller

import com.opensam.gateway.repository.AppUserRepository
import com.opensam.gateway.service.GatewayAdminAuthorizationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api/admin/users")
class AdminUsersController(
    private val appUserRepository: AppUserRepository,
    private val authorizationService: GatewayAdminAuthorizationService,
) {
    data class AdminUserSummary(
        val id: Long,
        val loginId: String,
        val displayName: String,
        val role: String,
        val grade: Int,
        val createdAt: OffsetDateTime,
        val lastLoginAt: OffsetDateTime?,
    )

    data class AdminUserAction(
        val type: String,
        val grade: Int? = null,
        val days: Int? = null,
        val oauthDays: Int? = null,
    )

    private fun currentLoginId(): String? = SecurityContextHolder.getContext().authentication?.name

    @GetMapping
    fun listUsers(): ResponseEntity<List<AdminUserSummary>> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            authorizationService.requireGlobalAdmin(loginId)
            val users = appUserRepository.findAll().map {
                AdminUserSummary(
                    id = it.id,
                    loginId = it.loginId,
                    displayName = it.displayName,
                    role = it.role,
                    grade = it.grade.toInt(),
                    createdAt = it.createdAt,
                    lastLoginAt = it.lastLoginAt,
                )
            }
            ResponseEntity.ok(users)
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }

    @PostMapping("/{id}/action")
    fun userAction(@PathVariable id: Long, @RequestBody action: AdminUserAction): ResponseEntity<Any> {
        val loginId = currentLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            authorizationService.requireGlobalAdmin(loginId)
            authorizationService.requireLowerGrade(loginId, id)

            val user = appUserRepository.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
            val actor = appUserRepository.findByLoginId(loginId) ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
            val actorGrade = actor.grade.toInt()

            when (action.type) {
                "delete" -> {
                    appUserRepository.delete(user)
                    return ResponseEntity.ok().build()
                }
                "setAdmin" -> {
                    if (actorGrade <= 6) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
                    user.grade = 6.toShort()
                    user.role = "ADMIN"
                }
                "removeAdmin" -> {
                    user.grade = 1.toShort()
                    user.role = "USER"
                }
                "setGrade" -> {
                    val next = action.grade ?: return ResponseEntity.badRequest().build()
                    if (next !in 0..7) return ResponseEntity.badRequest().build()
                    if (next >= actorGrade) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
                    user.grade = next.toShort()
                    user.role = if (next >= 5) "ADMIN" else "USER"
                }
                // Sanctions
                "block" -> {
                    val days = action.days ?: 3650
                    user.grade = 0.toShort()
                    user.role = "USER"
                    user.meta["blockedUntil"] = OffsetDateTime.now().plusDays(days.toLong()).toString()
                }
                "unblock" -> {
                    user.grade = 1.toShort()
                    user.role = "USER"
                    user.meta.remove("blockedUntil")
                }
                // Ops-only: record desired extension; the actual oauth integration lives in meta.
                "extendOauth" -> {
                    val days = action.oauthDays ?: return ResponseEntity.badRequest().build()
                    val current = (user.meta["oauthExpiresAt"] as? String)?.let {
                        runCatching { OffsetDateTime.parse(it) }.getOrNull()
                    }
                    val base = current?.takeIf { it.isAfter(OffsetDateTime.now()) } ?: OffsetDateTime.now()
                    user.meta["oauthExpiresAt"] = base.plusDays(days.toLong()).toString()
                }
                else -> return ResponseEntity.badRequest().build()
            }

            appUserRepository.save(user)
            ResponseEntity.ok().build()
        } catch (_: AccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
    }
}
