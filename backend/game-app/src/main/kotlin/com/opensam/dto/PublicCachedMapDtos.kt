package com.opensam.dto

import java.time.OffsetDateTime

data class PublicCachedMapCityResponse(
    val id: Long,
    val name: String,
    val x: Int,
    val y: Int,
    val nationName: String,
    val nationColor: String,
)

data class PublicCachedMapHistoryResponse(
    val id: Long,
    val sentAt: OffsetDateTime,
    val text: String,
    val year: Int? = null,
    val month: Int? = null,
    val events: List<String>? = null,
)

data class PublicCachedMapResponse(
    val available: Boolean,
    val worldId: Long?,
    val worldName: String?,
    val mapCode: String?,
    val cities: List<PublicCachedMapCityResponse>,
    val history: List<PublicCachedMapHistoryResponse>,
)
