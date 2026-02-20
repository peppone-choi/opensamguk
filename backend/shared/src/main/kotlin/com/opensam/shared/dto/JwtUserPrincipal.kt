package com.opensam.shared.dto

data class JwtUserPrincipal(
    val userId: Long,
    val loginId: String,
    val displayName: String,
    val role: String,
    val grade: Int,
)
