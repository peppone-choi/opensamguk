package com.opensam.gateway.dto

import com.opensam.gateway.entity.WorldState
import java.time.OffsetDateTime

data class WorldStateResponse(
    val id: Short,
    val name: String,
    val scenarioCode: String,
    val commitSha: String,
    val gameVersion: String,
    val currentYear: Short,
    val currentMonth: Short,
    val tickSeconds: Int,
    val realtimeMode: Boolean,
    val commandPointRegenRate: Int,
    val config: Map<String, Any>,
    val meta: Map<String, Any>,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(e: WorldState) = WorldStateResponse(
            id = e.id,
            name = e.name,
            scenarioCode = e.scenarioCode,
            commitSha = e.commitSha,
            gameVersion = e.gameVersion,
            currentYear = e.currentYear,
            currentMonth = e.currentMonth,
            tickSeconds = e.tickSeconds,
            realtimeMode = e.realtimeMode,
            commandPointRegenRate = e.commandPointRegenRate,
            config = e.config,
            meta = e.meta,
            updatedAt = e.updatedAt,
        )
    }
}
