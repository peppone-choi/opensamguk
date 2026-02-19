package com.opensam.dto

data class SimUnitInfo(
    val name: String = "장수",
    val nationId: Long = 1,
    val leadership: Int = 50,
    val strength: Int = 50,
    val intel: Int = 50,
    val crew: Int = 1000,
    val crewType: Int = 0,
    val train: Int = 50,
    val atmos: Int = 50,
    val weaponCode: String = "None",
    val bookCode: String = "None",
    val horseCode: String = "None",
    val specialCode: String = "None",
)

data class TerrainModifier(
    val attackMultiplier: Double = 1.0,
    val defenceMultiplier: Double = 1.0,
)

data class WeatherModifier(
    val attackMultiplier: Double = 1.0,
    val defenceMultiplier: Double = 1.0,
)

data class SimCityInfo(
    val worldId: Long = 0,
    val nationId: Long = 0,
    val name: String = "가상 도시",
    val def: Int = 0,
    val wall: Int = 0,
    val level: Int = 5,
)

data class SimulateRequest(
    val attacker: SimUnitInfo,
    val defender: SimUnitInfo,
    val defenders: List<SimUnitInfo> = emptyList(),
    val defenderCity: SimCityInfo = SimCityInfo(),
    val terrain: String = "plain",
    val weather: String = "clear",
)

data class SimulateResult(
    val winner: String,
    val attackerRemaining: Int,
    val defenderRemaining: Int,
    val defendersRemaining: List<Int> = emptyList(),
    val rounds: Int,
    val terrain: String,
    val weather: String,
    val logs: List<String>,
)
