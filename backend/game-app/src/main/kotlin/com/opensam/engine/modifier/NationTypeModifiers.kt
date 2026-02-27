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
        "che_도적" to object : ActionModifier {
            override val code = "che_도적"; override val name = "도적"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("치안", "치안강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("민심", "주민선정") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("인구", "정착장려") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                "계략" -> ctx.copy(successMultiplier = ctx.successMultiplier + 0.1)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(goldMultiplier = ctx.goldMultiplier * 0.9)
        },
        "che_명가" to object : ActionModifier {
            override val code = "che_명가"; override val name = "명가"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("기술", "기술연구") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("수비", "수비강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("성벽", "성벽보수") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(riceMultiplier = ctx.riceMultiplier * 0.9, popGrowthMultiplier = ctx.popGrowthMultiplier * 1.2)
        },
        "che_음양가" to object : ActionModifier {
            override val code = "che_음양가"; override val name = "음양가"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("농업", "농지개간") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("상업", "상업투자") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("기술", "기술연구") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(popGrowthMultiplier = ctx.popGrowthMultiplier * 1.2)
            override fun onCalcStrategic(ctx: StrategicContext) = ctx.copy(delayMultiplier = ctx.delayMultiplier * 1.33)
        },
        "che_종횡가" to object : ActionModifier {
            override val code = "che_종횡가"; override val name = "종횡가"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("수비", "수비강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("성벽", "성벽보수") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("농업", "농지개간") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("상업", "상업투자") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(goldMultiplier = ctx.goldMultiplier * 0.9)
            override fun onCalcStrategic(ctx: StrategicContext) = ctx.copy(delayMultiplier = ctx.delayMultiplier * 0.75, globalDelayMultiplier = ctx.globalDelayMultiplier * 0.5)
        },
        "che_불가" to object : ActionModifier {
            override val code = "che_불가"; override val name = "불가"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("민심", "주민선정") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("인구", "정착장려") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("수비", "수비강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("성벽", "성벽보수") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(goldMultiplier = ctx.goldMultiplier * 0.9)
        },
        "che_오두미도" to object : ActionModifier {
            override val code = "che_오두미도"; override val name = "오두미도"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("기술", "기술연구") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("수비", "수비강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("성벽", "성벽보수") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("농업", "농지개간") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("상업", "상업투자") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(riceMultiplier = ctx.riceMultiplier * 1.1, popGrowthMultiplier = ctx.popGrowthMultiplier * 1.2)
        },
        "che_태평도" to object : ActionModifier {
            override val code = "che_태평도"; override val name = "태평도"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("민심", "주민선정") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("인구", "정착장려") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("기술", "기술연구") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("수비", "수비강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("성벽", "성벽보수") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(popGrowthMultiplier = ctx.popGrowthMultiplier * 1.2)
        },
        "che_도가" to object : ActionModifier {
            override val code = "che_도가"; override val name = "도가"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("기술", "기술연구") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("치안", "치안강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(popGrowthMultiplier = ctx.popGrowthMultiplier * 1.2)
        },
        "che_묵가" to object : ActionModifier {
            override val code = "che_묵가"; override val name = "묵가"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("수비", "수비강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("성벽", "성벽보수") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("기술", "기술연구") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
        },
        "che_덕가" to object : ActionModifier {
            override val code = "che_덕가"; override val name = "덕가"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("치안", "치안강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("민심", "주민선정") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("인구", "정착장려") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("수비", "수비강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("성벽", "성벽보수") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(riceMultiplier = ctx.riceMultiplier * 0.9, popGrowthMultiplier = ctx.popGrowthMultiplier * 1.2)
        },
        "che_병가" to object : ActionModifier {
            override val code = "che_병가"; override val name = "병가"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("기술", "기술연구") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("수비", "수비강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("성벽", "성벽보수") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("민심", "주민선정") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("인구", "정착장려") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(popGrowthMultiplier = ctx.popGrowthMultiplier * 0.8)
        },
        "che_유가" to object : ActionModifier {
            override val code = "che_유가"; override val name = "유가"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("농업", "농지개간") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("상업", "상업투자") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("민심", "주민선정") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("인구", "정착장려") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(riceMultiplier = ctx.riceMultiplier * 0.9)
        },
        "che_법가" to object : ActionModifier {
            override val code = "che_법가"; override val name = "법가"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("치안", "치안강화") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.8)
                in listOf("민심", "주민선정") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                in listOf("인구", "정착장려") -> ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 0.9, costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
            override fun onCalcIncome(ctx: IncomeContext) = ctx.copy(goldMultiplier = ctx.goldMultiplier * 1.1, popGrowthMultiplier = ctx.popGrowthMultiplier * 0.8)
        },
    )

    fun get(code: String): ActionModifier? = nationTypes[code]
    fun getAll(): Map<String, ActionModifier> = nationTypes
}
