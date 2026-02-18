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
        "che_안전" to object : ActionModifier {
            override val code = "che_안전"; override val name = "안전"
            override fun onCalcStat(stat: StatContext) = stat.copy(bonusAtmos = stat.bonusAtmos - 5)
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("징병", "모병") -> ctx.copy(costMultiplier = ctx.costMultiplier * 0.8)
                else -> ctx
            }
        },
        "che_유지" to object : ActionModifier {
            override val code = "che_유지"; override val name = "유지"
            override fun onCalcStat(stat: StatContext) = stat.copy(bonusTrain = stat.bonusTrain - 5)
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("징병", "모병") -> ctx.copy(costMultiplier = ctx.costMultiplier * 0.8)
                else -> ctx
            }
        },
        "che_재간" to object : ActionModifier {
            override val code = "che_재간"; override val name = "재간"
            override fun onCalcStat(stat: StatContext) = stat.copy(expMultiplier = stat.expMultiplier * 0.9)
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("징병", "모병") -> ctx.copy(costMultiplier = ctx.costMultiplier * 0.8)
                else -> ctx
            }
        },
        "che_출세" to object : ActionModifier {
            override val code = "che_출세"; override val name = "출세"
            override fun onCalcStat(stat: StatContext) = stat.copy(expMultiplier = stat.expMultiplier * 1.1)
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("징병", "모병") -> ctx.copy(costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
        },
        "che_할거" to object : ActionModifier {
            override val code = "che_할거"; override val name = "할거"
            override fun onCalcStat(stat: StatContext) = stat.copy(expMultiplier = stat.expMultiplier * 0.9, bonusTrain = stat.bonusTrain + 5)
        },
        "che_정복" to object : ActionModifier {
            override val code = "che_정복"; override val name = "정복"
            override fun onCalcStat(stat: StatContext) = stat.copy(expMultiplier = stat.expMultiplier * 0.9, bonusAtmos = stat.bonusAtmos + 5)
        },
        "che_패권" to object : ActionModifier {
            override val code = "che_패권"; override val name = "패권"
            override fun onCalcStat(stat: StatContext) = stat.copy(bonusTrain = stat.bonusTrain + 5)
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("징병", "모병") -> ctx.copy(costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
        },
        "che_의협" to object : ActionModifier {
            override val code = "che_의협"; override val name = "의협"
            override fun onCalcStat(stat: StatContext) = stat.copy(bonusAtmos = stat.bonusAtmos + 5)
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("징병", "모병") -> ctx.copy(costMultiplier = ctx.costMultiplier * 1.2)
                else -> ctx
            }
        },
        "che_대의" to object : ActionModifier {
            override val code = "che_대의"; override val name = "대의"
            override fun onCalcStat(stat: StatContext) = stat.copy(expMultiplier = stat.expMultiplier * 1.1, bonusTrain = stat.bonusTrain - 5)
        },
        "che_왕좌" to object : ActionModifier {
            override val code = "che_왕좌"; override val name = "왕좌"
            override fun onCalcStat(stat: StatContext) = stat.copy(expMultiplier = stat.expMultiplier * 1.1, bonusAtmos = stat.bonusAtmos - 5)
        },
        "che_은둔" to object : ActionModifier {
            override val code = "che_은둔"; override val name = "은둔"
            override fun onCalcStat(stat: StatContext) = stat.copy(expMultiplier = stat.expMultiplier * 0.9, dedicationMultiplier = stat.dedicationMultiplier * 0.9, bonusAtmos = stat.bonusAtmos - 5, bonusTrain = stat.bonusTrain - 5)
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                "단련" -> ctx.copy(successMultiplier = ctx.successMultiplier + 0.1)
                else -> ctx
            }
        },
    )

    fun get(code: String): ActionModifier? = personalities[code]
    fun getAll(): Map<String, ActionModifier> = personalities
}
