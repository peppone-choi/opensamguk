package com.opensam.shared.dto

import jakarta.validation.constraints.NotBlank

data class CreateWorldRequest(
    @field:NotBlank val scenarioCode: String,
    val name: String? = null,
    val tickSeconds: Int = 300,
    val commitSha: String? = null,
    val gameVersion: String? = null,
)

data class ResetWorldRequest(
    val scenarioCode: String? = null,
)
