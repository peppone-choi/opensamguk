package com.opensam.command

data class CommandEnv(
    val year: Int,
    val month: Int,
    val startYear: Int,
    val worldId: Long,
    val realtimeMode: Boolean = false,
    val develCost: Int = 100,
    val gameStor: MutableMap<String, Any> = mutableMapOf()
)
