package com.opensam.dto

data class InheritanceInfo(
    val points: Int,
    val previousPoints: Int? = null,
    val newPoints: Int? = null,
    val pointSources: List<PointSource>? = null,
    val pointBreakdown: Map<String, Int>? = null,
    val buffs: Map<String, Int>,
    val inheritBuff: Map<String, Int>? = null,
    val maxInheritBuff: Int? = null,
    val log: List<InheritanceLogEntry>,
    val turnResetCount: Int? = null,
    val specialWarResetCount: Int? = null,
    val inheritActionCost: InheritanceActionCost? = null,
    val availableSpecialWar: Map<String, SpecialWarOption>? = null,
    val availableUnique: Map<String, UniqueItemOption>? = null,
    val availableTargetGeneral: Map<Long, String>? = null,
    val currentStat: CurrentStat? = null,
)

data class PointSource(
    val label: String,
    val amount: Int,
)

data class InheritanceActionCost(
    val buff: List<Int> = listOf(0, 100, 200, 400, 800, 1600),
    val resetTurnTime: Int = 100,
    val resetSpecialWar: Int = 200,
    val randomUnique: Int = 300,
    val nextSpecial: Int = 500,
    val minSpecificUnique: Int = 500,
    val checkOwner: Int = 50,
    val bornStatPoint: Int = 500,
)

data class SpecialWarOption(
    val title: String,
    val info: String = "",
)

data class UniqueItemOption(
    val title: String,
    val rawName: String = "",
    val info: String = "",
)

data class CurrentStat(
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val statMax: Int = 100,
    val statMin: Int = 10,
)

data class InheritanceLogEntry(
    val id: Long? = null,
    val action: String,
    val amount: Int,
    val date: String,
    val text: String? = null,
)

data class BuyBuffRequest(val buffCode: String)

data class BuyInheritBuffRequest(val type: String, val level: Int)

data class ResetStatsRequest(
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val inheritBonusStat: List<Int>? = null,
)

data class CheckOwnerRequest(
    val destGeneralID: Long? = null,
    val generalName: String? = null,
)

data class CheckOwnerResponse(
    val ownerName: String? = null,
    val found: Boolean,
)

data class AuctionUniqueRequest(
    val uniqueCode: String,
    val bidAmount: Int,
)

data class InheritanceActionResult(
    val remainingPoints: Int? = null,
    val newLevel: Int? = null,
    val error: String? = null,
)

data class SetInheritSpecialRequest(val specialCode: String)

data class SetInheritCityRequest(val cityId: Long)
