package com.opensam.gateway.service

import com.opensam.gateway.repository.AppUserRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service

@Service
class GatewayAdminAuthorizationService(
    private val appUserRepository: AppUserRepository,
) {
    private companion object {
        const val GRADE_SYSTEM_ADMIN = 6
    }

    fun requireGlobalAdmin(loginId: String) {
        val user = appUserRepository.findByLoginId(loginId) ?: throw AccessDeniedException("user not found")
        if (user.grade.toInt() < GRADE_SYSTEM_ADMIN) {
            throw AccessDeniedException("global admin required")
        }
    }

    fun requireLowerGrade(actorLoginId: String, targetUserId: Long) {
        val actor = appUserRepository.findByLoginId(actorLoginId) ?: throw AccessDeniedException("actor not found")
        val target = appUserRepository.findById(targetUserId).orElse(null) ?: throw AccessDeniedException("target not found")
        if (target.grade.toInt() >= actor.grade.toInt()) {
            throw AccessDeniedException("cannot modify same/higher grade")
        }
    }
}
