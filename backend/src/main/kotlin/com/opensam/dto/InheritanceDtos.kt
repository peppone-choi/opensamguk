package com.opensam.dto

data class InheritanceInfo(
    val points: Int,
    val buffs: Map<String, Int>,
    val log: List<InheritanceLogEntry>,
)

data class InheritanceLogEntry(
    val action: String,
    val amount: Int,
    val date: String,
)

data class BuyBuffRequest(val buffCode: String)

data class InheritanceActionResult(
    val remainingPoints: Int? = null,
    val newLevel: Int? = null,
    val error: String? = null,
)

data class SetInheritSpecialRequest(val specialCode: String)

data class SetInheritCityRequest(val cityId: Long)
