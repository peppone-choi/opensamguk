package com.opensam.command

data class CommandEnv(
    val year: Int,
    val month: Int,
    val startYear: Int,
    val worldId: Long,
    val realtimeMode: Boolean = false,
    val develCost: Int = 100,
    val scenario: Int = 0,
    val exchangeFee: Double = 0.03,
    val initialNationGenLimit: Int = 5,
    val gameStor: MutableMap<String, Any> = mutableMapOf()
) {
    /**
     * Legacy TechLimit: checks if current tech exceeds the year-based limit.
     */
    fun isTechLimited(currentTech: Double): Boolean {
        val relYear = year - startYear
        val limit = relYear * 1000.0 // Simplified; actual formula from legacy
        return currentTech >= limit
    }
}

