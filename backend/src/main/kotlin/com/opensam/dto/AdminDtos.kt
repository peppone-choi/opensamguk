package com.opensam.dto

import java.time.OffsetDateTime

data class AdminGeneralAction(val type: String)

data class AdminUserAction(val type: String)

data class TimeControlRequest(val year: Int? = null, val month: Int? = null, val locked: Boolean? = null)

data class NationStatistic(
    val nationId: Long, val name: String, val color: String, val level: Int,
    val gold: Int, val rice: Int, val tech: Float, val power: Int,
    val genCount: Int, val cityCount: Int, val totalCrew: Int, val totalPop: Int,
)

data class AdminDashboard(
    val worldCount: Int,
    val currentWorld: AdminWorldInfo?,
)

data class AdminWorldInfo(
    val id: Short,
    val year: Short,
    val month: Short,
    val scenarioCode: String,
    val realtimeMode: Boolean,
    val config: MutableMap<String, Any>,
)

data class AdminGeneralSummary(
    val id: Long,
    val name: String,
    val nationId: Long,
    val crew: Int,
    val experience: Int,
    val npcState: Int,
    val blockState: Int,
)

data class AdminUserSummary(
    val id: Long,
    val loginId: String,
    val displayName: String,
    val role: String,
    val createdAt: OffsetDateTime,
    val lastLoginAt: OffsetDateTime?,
)
