package com.opensam.dto

data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

data class UpdateSettingsRequest(
    val defenceTrain: Int? = null,
    val tournamentState: Int? = null,
    val potionThreshold: Int? = null,
    val autoNationTurn: Boolean? = null,
    val preRiseDelete: Boolean? = null,
    val preOpenDelete: Boolean? = null,
    val borderReturn: Boolean? = null,
    val customCss: String? = null,
)
