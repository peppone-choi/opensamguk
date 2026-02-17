package com.opensam.service

import com.opensam.config.JwtUtil
import com.opensam.dto.*
import com.opensam.entity.AppUser
import com.opensam.repository.AppUserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class AuthService(
    private val userRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtUtil: JwtUtil
) {

    fun register(request: RegisterRequest): AuthResponse {
        if (userRepository.existsByLoginId(request.loginId)) {
            throw IllegalArgumentException("Login ID already exists")
        }

        val user = AppUser(
            loginId = request.loginId,
            displayName = request.displayName,
            passwordHash = passwordEncoder.encode(request.password)
        )
        val saved = userRepository.save(user)

        val token = jwtUtil.generateToken(saved.id, saved.loginId, saved.displayName, saved.role)
        return AuthResponse(token, UserInfo(saved.id, saved.loginId, saved.displayName))
    }

    fun login(request: LoginRequest): AuthResponse {
        val user = userRepository.findByLoginId(request.loginId)
            ?: throw IllegalArgumentException("Invalid credentials")

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw IllegalArgumentException("Invalid credentials")
        }

        user.lastLoginAt = OffsetDateTime.now()
        userRepository.save(user)

        val token = jwtUtil.generateToken(user.id, user.loginId, user.displayName, user.role)
        return AuthResponse(token, UserInfo(user.id, user.loginId, user.displayName))
    }
}
