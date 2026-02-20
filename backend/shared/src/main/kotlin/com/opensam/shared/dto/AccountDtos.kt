package com.opensam.shared.dto

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

data class UpdateSettingsRequest(
    val defenceTrain: Int? = null,
    val tournamentState: Int? = null,
)
