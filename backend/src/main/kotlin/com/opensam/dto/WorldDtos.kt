package com.opensam.dto

import jakarta.validation.constraints.NotBlank

data class CreateWorldRequest(
    @field:NotBlank val scenarioCode: String,
    val name: String? = null,
    val tickSeconds: Int = 300,
)

data class ResetWorldRequest(
    val scenarioCode: String? = null,
)
