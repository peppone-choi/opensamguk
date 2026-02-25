package com.opensam.dto

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

data class WorldCityOwnershipSnapshotResponse(
    val cityId: Long,
    val nationId: Long,
)

data class WorldSnapshotResponse(
    val id: Long,
    val worldId: Long,
    val year: Int,
    val month: Int,
    val createdAt: String,
    val phase: String? = null,
    val season: String? = null,
    val cityOwnership: List<WorldCityOwnershipSnapshotResponse>,
    val events: List<String> = emptyList(),
)
