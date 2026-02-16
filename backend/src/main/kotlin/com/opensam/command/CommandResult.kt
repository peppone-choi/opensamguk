package com.opensam.command

data class CommandResult(
    val success: Boolean,
    val logs: List<String> = emptyList(),
    val message: String? = null
)

data class CommandCost(
    val gold: Int = 0,
    val rice: Int = 0
)
