package com.opensam.engine.modifier

interface ActionModifier {
    val code: String
    val name: String

    // Domestic command modifiers
    fun onCalcDomestic(ctx: DomesticContext): DomesticContext = ctx

    // Stat modifiers for battle
    fun onCalcStat(stat: StatContext): StatContext = stat

    // Opponent stat modifiers (e.g., 반계 reduces opponent magic, 견고 reduces opponent critical)
    fun onCalcOpposeStat(stat: StatContext): StatContext = stat

    // War power multiplier
    fun getWarPowerMultiplier(): Double = 1.0

    // Strategic command modifiers
    fun onCalcStrategic(ctx: StrategicContext): StrategicContext = ctx

    // Income modifiers
    fun onCalcIncome(ctx: IncomeContext): IncomeContext = ctx
}

data class DomesticContext(
    var costMultiplier: Double = 1.0,
    var successMultiplier: Double = 1.0,
    var failMultiplier: Double = 1.0,
    var scoreMultiplier: Double = 1.0,
    var trainMultiplier: Double = 1.0,
    var atmosMultiplier: Double = 1.0,
    val actionCode: String = "",
)

data class StatContext(
    var leadership: Double = 0.0,
    var strength: Double = 0.0,
    var intel: Double = 0.0,
    var criticalChance: Double = 0.05,
    var dodgeChance: Double = 0.05,
    var magicChance: Double = 0.0,
    var warPower: Double = 1.0,
    var bonusTrain: Double = 0.0,
    var bonusAtmos: Double = 0.0,
    var magicTrialProb: Double = 0.0,
    var magicSuccessProb: Double = 0.0,
    var magicSuccessDamage: Double = 1.0,
    var dexMultiplier: Double = 1.0,
    var expMultiplier: Double = 1.0,
    var injuryProb: Double = 0.0,
    var initWarPhase: Double = 0.0,
    var sabotageDefence: Double = 0.0,
    var dedicationMultiplier: Double = 1.0,
    var year: Int = 0,
    var startYear: Int = 0,
)

data class StrategicContext(
    var delayMultiplier: Double = 1.0,
    var costMultiplier: Double = 1.0,
    var globalDelayMultiplier: Double = 1.0,
)

data class IncomeContext(
    var goldMultiplier: Double = 1.0,
    var riceMultiplier: Double = 1.0,
    var popGrowthMultiplier: Double = 1.0,
)
