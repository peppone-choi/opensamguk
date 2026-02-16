package com.opensam.dto

data class SimUnitInfo(
    val name: String = "장수",
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

data class SimCityInfo(
    val def: Int = 0,
    val wall: Int = 0,
    val level: Int = 5,
)

data class SimulateRequest(
    val attacker: SimUnitInfo,
    val defender: SimUnitInfo,
    val defenderCity: SimCityInfo = SimCityInfo(),
)

data class SimulateResult(
    val winner: String,
    val attackerRemaining: Int,
    val defenderRemaining: Int,
    val rounds: Int,
    val logs: List<String>,
)
