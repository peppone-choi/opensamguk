package com.opensam.engine.modifier

object NationTypeModifiers {

    private val nationTypes = mapOf<String, ActionModifier>(
        "che_중립" to object : ActionModifier {
            override val code = "che_중립"; override val name = "중립"
        },
        "che_군벌" to object : ActionModifier {
            override val code = "che_군벌"; override val name = "군벌"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.05)
        },
        "che_왕도" to object : ActionModifier {
            override val code = "che_왕도"; override val name = "왕도"
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.15)
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(popGrowthMultiplier = ctx.popGrowthMultiplier * 1.1)
        },
        "che_패도" to object : ActionModifier {
            override val code = "che_패도"; override val name = "패도"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.1)
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.95)
        },
        "che_상인" to object : ActionModifier {
            override val code = "che_상인"; override val name = "상인"
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(goldMultiplier = ctx.goldMultiplier * 1.2)
        },
        "che_농업국" to object : ActionModifier {
            override val code = "che_농업국"; override val name = "농업국"
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(riceMultiplier = ctx.riceMultiplier * 1.2, popGrowthMultiplier = ctx.popGrowthMultiplier * 1.05)
        },
        "che_유목" to object : ActionModifier {
            override val code = "che_유목"; override val name = "유목"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.08, dodgeChance = stat.dodgeChance + 0.03)
        },
        "che_해적" to object : ActionModifier {
            override val code = "che_해적"; override val name = "해적"
            override fun onCalcStat(stat: StatContext) = stat.copy(criticalChance = stat.criticalChance + 0.05, warPower = stat.warPower * 1.05)
        },
        "che_황건" to object : ActionModifier {
            override val code = "che_황건"; override val name = "황건"
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(costMultiplier = ctx.costMultiplier * 0.8)
            override fun onCalcStat(stat: StatContext) = stat.copy(magicChance = stat.magicChance + 0.1)
        },
        "che_종교" to object : ActionModifier {
            override val code = "che_종교"; override val name = "종교"
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(successMultiplier = ctx.successMultiplier * 1.1)
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(popGrowthMultiplier = ctx.popGrowthMultiplier * 1.15)
        },
        "che_학문" to object : ActionModifier {
            override val code = "che_학문"; override val name = "학문"
            override fun onCalcStat(stat: StatContext) = stat.copy(intel = stat.intel + 3)
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1)
        },
        "che_명문" to object : ActionModifier {
            override val code = "che_명문"; override val name = "명문"
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.05, successMultiplier = ctx.successMultiplier * 1.05)
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.03)
        },
        "che_의적" to object : ActionModifier {
            override val code = "che_의적"; override val name = "의적"
            override fun onCalcStat(stat: StatContext) = stat.copy(criticalChance = stat.criticalChance + 0.03)
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.05)
        },
        "che_은둔" to object : ActionModifier {
            override val code = "che_은둔"; override val name = "은둔"
            override fun onCalcStat(stat: StatContext) = stat.copy(dodgeChance = stat.dodgeChance + 0.05)
        },
        "che_무사" to object : ActionModifier {
            override val code = "che_무사"; override val name = "무사"
            override fun onCalcStat(stat: StatContext) = stat.copy(strength = stat.strength + 3, warPower = stat.warPower * 1.05)
        },
        "che_건국" to object : ActionModifier {
            override val code = "che_건국"; override val name = "건국"
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1)
        },
    )

    fun get(code: String): ActionModifier? = nationTypes[code]
    fun getAll(): Map<String, ActionModifier> = nationTypes
}
