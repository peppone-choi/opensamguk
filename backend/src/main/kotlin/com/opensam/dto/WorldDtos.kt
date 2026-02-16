package com.opensam.dto

import jakarta.validation.constraints.NotBlank

data class CreateWorldRequest(
    @field:NotBlank val scenarioCode: String,
    val tickSeconds: Int = 300,
)
