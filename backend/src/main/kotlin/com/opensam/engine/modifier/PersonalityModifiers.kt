package com.opensam.engine.modifier

object PersonalityModifiers {

    private val personalities = mapOf<String, ActionModifier>(
        "호전" to object : ActionModifier {
            override val code = "호전"; override val name = "호전적"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.05)
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.95)
        },
        "냉정" to object : ActionModifier {
            override val code = "냉정"; override val name = "냉정"
            override fun onCalcStat(stat: StatContext) = stat.copy(magicChance = stat.magicChance + 0.05)
        },
        "대담" to object : ActionModifier {
            override val code = "대담"; override val name = "대담"
            override fun onCalcStat(stat: StatContext) = stat.copy(criticalChance = stat.criticalChance + 0.03, warPower = stat.warPower * 1.03)
        },
        "신중" to object : ActionModifier {
            override val code = "신중"; override val name = "신중"
            override fun onCalcStat(stat: StatContext) = stat.copy(dodgeChance = stat.dodgeChance + 0.05)
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(successMultiplier = ctx.successMultiplier * 1.1)
        },
        "온후" to object : ActionModifier {
            override val code = "온후"; override val name = "온후"
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1)
        },
        "명석" to object : ActionModifier {
            override val code = "명석"; override val name = "명석"
            override fun onCalcStat(stat: StatContext) = stat.copy(intel = stat.intel + 2)
        },
        "강직" to object : ActionModifier {
            override val code = "강직"; override val name = "강직"
            override fun onCalcStat(stat: StatContext) = stat.copy(leadership = stat.leadership + 2)
        },
        "의리" to object : ActionModifier {
            override val code = "의리"; override val name = "의리"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.02)
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.05)
        },
        "탐욕" to object : ActionModifier {
            override val code = "탐욕"; override val name = "탐욕"
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(goldMultiplier = ctx.goldMultiplier * 1.1)
        },
        "겁쟁이" to object : ActionModifier {
            override val code = "겁쟁이"; override val name = "겁쟁이"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 0.9, dodgeChance = stat.dodgeChance + 0.1)
        },
        "포악" to object : ActionModifier {
            override val code = "포악"; override val name = "포악"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.08)
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9)
        },
        "도적" to object : ActionModifier {
            override val code = "도적"; override val name = "도적"
            override fun onCalcStat(stat: StatContext) = stat.copy(criticalChance = stat.criticalChance + 0.05)
        },
        "일반" to object : ActionModifier {
            override val code = "일반"; override val name = "일반"
        },
    )

    fun get(code: String): ActionModifier? = personalities[code]
    fun getAll(): Map<String, ActionModifier> = personalities
}
