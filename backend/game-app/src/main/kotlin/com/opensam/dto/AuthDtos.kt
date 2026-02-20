package com.opensam.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class LoginRequest(
    @field:NotBlank val loginId: String,
    @field:NotBlank val password: String
)

data class RegisterRequest(
    @field:NotBlank @field:Size(min = 3, max = 20) val loginId: String,
    @field:NotBlank @field:Size(min = 2, max = 20) val displayName: String,
    @field:NotBlank @field:Size(min = 6, max = 100) val password: String
)

data class AuthResponse(
    val token: String,
    val user: UserInfo
)

data class UserInfo(
    val id: Long,
    val loginId: String,
    val displayName: String
)
