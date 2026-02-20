package com.opensam.gateway.service

import com.opensam.gateway.config.JwtUtil
import com.opensam.gateway.entity.AppUser
import com.opensam.gateway.repository.AppUserRepository
import com.opensam.shared.dto.AuthResponse
import com.opensam.shared.dto.LoginRequest
import com.opensam.shared.dto.RegisterRequest
import com.opensam.shared.dto.UserInfo
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class AuthService(
    private val appUserRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtUtil: JwtUtil,
) {
    fun register(request: RegisterRequest): AuthResponse {
        if (appUserRepository.existsByLoginId(request.loginId)) {
            throw IllegalArgumentException("Login ID already exists")
        }

        val user = AppUser(
            loginId = request.loginId,
            displayName = request.displayName,
            passwordHash = passwordEncoder.encode(request.password),
        )
        val saved = appUserRepository.save(user)

        val role = effectiveRole(saved)
        val token = jwtUtil.generateToken(saved.id, saved.loginId, saved.displayName, role, saved.grade.toInt())
        return AuthResponse(token, UserInfo(saved.id, saved.loginId, saved.displayName))
    }

    fun login(request: LoginRequest): AuthResponse {
        val user = appUserRepository.findByLoginId(request.loginId)
            ?: throw IllegalArgumentException("Invalid credentials")

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw IllegalArgumentException("Invalid credentials")
        }

        val role = effectiveRole(user)
        user.role = role
        user.lastLoginAt = OffsetDateTime.now()
        appUserRepository.save(user)

        val token = jwtUtil.generateToken(user.id, user.loginId, user.displayName, role, user.grade.toInt())
        return AuthResponse(token, UserInfo(user.id, user.loginId, user.displayName))
    }

    private fun effectiveRole(user: AppUser): String {
        return if (user.grade.toInt() >= 5) "ADMIN" else "USER"
    }
}
