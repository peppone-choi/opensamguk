package com.opensam.engine.modifier

import com.opensam.model.ArmType
import com.opensam.model.CrewType
import kotlin.math.log2

object SpecialModifiers {

    private fun isRegionalOrCityCrewType(raw: String): Boolean {
        val code = raw.toIntOrNull() ?: return false
        if (code <= 0) return true
        val crewType = CrewType.fromCode(code) ?: return false
        if (crewType.armType == ArmType.CASTLE) return true
        return crewType.code % 100 != 0
    }

    private val specials = mapOf<String, ActionModifier>(
        // === War Specials (22) ===
        "기병" to object : ActionModifier {
            override val code = "기병"; override val name = "기병"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.1)
        },
        "보병" to object : ActionModifier {
            override val code = "보병"; override val name = "보병"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.1)
        },
        "궁병" to object : ActionModifier {
            override val code = "궁병"; override val name = "궁병"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.1)
        },
        "필살" to object : ActionModifier {
            override val code = "필살"; override val name = "필살"
            override fun onCalcStat(stat: StatContext) = stat.copy(criticalChance = stat.criticalChance + 0.1)
        },
        "회피" to object : ActionModifier {
            override val code = "회피"; override val name = "회피"
            override fun onCalcStat(stat: StatContext) = stat.copy(dodgeChance = stat.dodgeChance + 0.1)
        },
        "화공" to object : ActionModifier {
            override val code = "화공"; override val name = "화공"
            override fun onCalcStat(stat: StatContext) = stat.copy(magicChance = stat.magicChance + 0.15)
        },
        "기습" to object : ActionModifier {
            override val code = "기습"; override val name = "기습"
            override fun onCalcStat(stat: StatContext) = stat.copy(criticalChance = stat.criticalChance + 0.05, dodgeChance = stat.dodgeChance + 0.05)
        },
        "저격" to object : ActionModifier {
            override val code = "저격"; override val name = "저격"
            override fun onCalcStat(stat: StatContext) = stat.copy(criticalChance = stat.criticalChance + 0.08)
        },
        "매복" to object : ActionModifier {
            override val code = "매복"; override val name = "매복"
            override fun onCalcStat(stat: StatContext) = stat.copy(dodgeChance = stat.dodgeChance + 0.08)
        },
        "방어" to object : ActionModifier {
            override val code = "방어"; override val name = "방어"
            override fun getWarPowerMultiplier() = 0.9
            override fun onCalcStat(stat: StatContext) = stat.copy(dodgeChance = stat.dodgeChance + 0.15)
        },
        "돌격" to object : ActionModifier {
            override val code = "돌격"; override val name = "돌격"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.15, dodgeChance = stat.dodgeChance - 0.03)
        },
        "반계" to object : ActionModifier {
            override val code = "반계"; override val name = "반계"
            override fun onCalcStat(stat: StatContext) = stat.copy(magicChance = stat.magicChance + 0.1)
        },
        "신산" to object : ActionModifier {
            override val code = "신산"; override val name = "신산"
            override fun onCalcStat(stat: StatContext) = stat.copy(magicChance = stat.magicChance + 0.2)
        },
        "귀모" to object : ActionModifier {
            override val code = "귀모"; override val name = "귀모"
            override fun onCalcStat(stat: StatContext) = stat.copy(magicChance = stat.magicChance + 0.25, warPower = stat.warPower * 0.95)
        },
        "수군" to object : ActionModifier {
            override val code = "수군"; override val name = "수군"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.05)
        },
        "연사" to object : ActionModifier {
            override val code = "연사"; override val name = "연사"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.08)
        },
        "공성" to object : ActionModifier {
            override val code = "공성"; override val name = "공성"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.1)
        },
        "위압" to object : ActionModifier {
            override val code = "위압"; override val name = "위압"
            override fun onCalcStat(stat: StatContext) = stat.copy(leadership = stat.leadership + 5)
        },
        "격노" to object : ActionModifier {
            override val code = "격노"; override val name = "격노"
            override fun onCalcStat(stat: StatContext) = stat.copy(warPower = stat.warPower * 1.2, dodgeChance = stat.dodgeChance - 0.05)
        },
        "분투" to object : ActionModifier {
            override val code = "분투"; override val name = "분투"
            override fun onCalcStat(stat: StatContext) = stat.copy(strength = stat.strength + 3, warPower = stat.warPower * 1.05)
        },
        "용병" to object : ActionModifier {
            override val code = "용병"; override val name = "용병"
            override fun onCalcStat(stat: StatContext) = stat.copy(leadership = stat.leadership + 3, warPower = stat.warPower * 1.05)
        },
        "철벽" to object : ActionModifier {
            override val code = "철벽"; override val name = "철벽"
            override fun onCalcStat(stat: StatContext) = stat.copy(dodgeChance = stat.dodgeChance + 0.12, warPower = stat.warPower * 0.95)
        },

        // === Domestic Specials ===
        "농업" to object : ActionModifier {
            override val code = "농업"; override val name = "농업"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode == "농지개간")
                ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.2) else ctx
        },
        "상업" to object : ActionModifier {
            override val code = "상업"; override val name = "상업"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode == "상업투자")
                ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.2) else ctx
        },
        "징수" to object : ActionModifier {
            override val code = "징수"; override val name = "징수"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode == "물자조달")
                ctx.copy(costMultiplier = ctx.costMultiplier * 0.8) else ctx
        },
        "보수" to object : ActionModifier {
            override val code = "보수"; override val name = "보수"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode in listOf("수비강화", "성벽보수"))
                ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.3) else ctx
        },
        "발명" to object : ActionModifier {
            override val code = "발명"; override val name = "발명"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode == "기술연구")
                ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.5) else ctx
        },
        "의술" to object : ActionModifier {
            override val code = "의술"; override val name = "의술"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode == "요양")
                ctx.copy(successMultiplier = ctx.successMultiplier * 1.3) else ctx
        },
        "치료" to object : ActionModifier {
            override val code = "치료"; override val name = "치료"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode == "요양")
                ctx.copy(successMultiplier = ctx.successMultiplier * 1.5) else ctx
        },
        "인덕" to object : ActionModifier {
            override val code = "인덕"; override val name = "인덕"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode in listOf("주민선정", "정착장려"))
                ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.2) else ctx
        },
        "등용" to object : ActionModifier {
            override val code = "등용"; override val name = "등용"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode == "등용")
                ctx.copy(successMultiplier = ctx.successMultiplier * 1.5) else ctx
        },
        "정치" to object : ActionModifier {
            override val code = "정치"; override val name = "정치"
            override fun onCalcDomestic(ctx: DomesticContext) = ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.1, costMultiplier = ctx.costMultiplier * 0.9)
        },
        "건축" to object : ActionModifier {
            override val code = "건축"; override val name = "건축"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode in listOf("수비강화", "성벽보수"))
                ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.3) else ctx
        },
        "훈련_특기" to object : ActionModifier {
            override val code = "훈련_특기"; override val name = "훈련"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode == "훈련")
                ctx.copy(trainMultiplier = ctx.trainMultiplier * 1.3) else ctx
        },
        "모병_특기" to object : ActionModifier {
            override val code = "모병_특기"; override val name = "모병"
            override fun onCalcDomestic(ctx: DomesticContext) = if (ctx.actionCode in listOf("모병", "징병"))
                ctx.copy(scoreMultiplier = ctx.scoreMultiplier * 1.3) else ctx
        },
        "che_인덕" to object : ActionModifier {
            override val code = "che_인덕"; override val name = "인덕"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("민심", "주민선정", "인구", "정착장려") -> ctx.copy(
                    scoreMultiplier = ctx.scoreMultiplier * 1.1,
                    successMultiplier = ctx.successMultiplier + 0.1,
                    costMultiplier = ctx.costMultiplier * 0.8
                )
                else -> ctx
            }
        },
        "che_발명" to object : ActionModifier {
            override val code = "che_발명"; override val name = "발명"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("기술", "기술연구") -> ctx.copy(
                    scoreMultiplier = ctx.scoreMultiplier * 1.1,
                    successMultiplier = ctx.successMultiplier + 0.1,
                    costMultiplier = ctx.costMultiplier * 0.8
                )
                else -> ctx
            }
        },
        "che_경작" to object : ActionModifier {
            override val code = "che_경작"; override val name = "경작"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("농업", "농지개간") -> ctx.copy(
                    scoreMultiplier = ctx.scoreMultiplier * 1.1,
                    successMultiplier = ctx.successMultiplier + 0.1,
                    costMultiplier = ctx.costMultiplier * 0.8
                )
                else -> ctx
            }
        },
        "che_상재" to object : ActionModifier {
            override val code = "che_상재"; override val name = "상재"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("상업", "상업투자") -> ctx.copy(
                    scoreMultiplier = ctx.scoreMultiplier * 1.1,
                    successMultiplier = ctx.successMultiplier + 0.1,
                    costMultiplier = ctx.costMultiplier * 0.8
                )
                else -> ctx
            }
        },
        "che_축성" to object : ActionModifier {
            override val code = "che_축성"; override val name = "축성"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("성벽", "성벽보수") -> ctx.copy(
                    scoreMultiplier = ctx.scoreMultiplier * 1.1,
                    successMultiplier = ctx.successMultiplier + 0.1,
                    costMultiplier = ctx.costMultiplier * 0.8
                )
                else -> ctx
            }
        },
        "che_수비" to object : ActionModifier {
            override val code = "che_수비"; override val name = "수비"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("수비", "수비강화") -> ctx.copy(
                    scoreMultiplier = ctx.scoreMultiplier * 1.1,
                    successMultiplier = ctx.successMultiplier + 0.1,
                    costMultiplier = ctx.costMultiplier * 0.8
                )
                else -> ctx
            }
        },
        "che_통찰" to object : ActionModifier {
            override val code = "che_통찰"; override val name = "통찰"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("치안", "치안강화") -> ctx.copy(
                    scoreMultiplier = ctx.scoreMultiplier * 1.1,
                    successMultiplier = ctx.successMultiplier + 0.1,
                    costMultiplier = ctx.costMultiplier * 0.8
                )
                else -> ctx
            }
        },
        "che_귀모" to object : ActionModifier {
            override val code = "che_귀모"; override val name = "귀모"
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                "계략" -> ctx.copy(successMultiplier = ctx.successMultiplier + 0.2)
                else -> ctx
            }
        },
        "che_귀병" to object : ActionModifier {
            override val code = "che_귀병"; override val name = "귀병"
            override fun onCalcStat(stat: StatContext) = stat.copy(
                magicSuccessProb = stat.magicSuccessProb + 0.2,
                dexMultiplier = stat.dexMultiplier * 1.1
            )
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("징병", "모병") -> ctx.copy(costMultiplier = ctx.costMultiplier * 0.9)
                else -> ctx
            }
        },
        "che_신산" to object : ActionModifier {
            override val code = "che_신산"; override val name = "신산"
            override fun onCalcStat(stat: StatContext) = stat.copy(
                magicTrialProb = stat.magicTrialProb + 0.2,
                magicSuccessProb = stat.magicSuccessProb + 0.2
            )
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                "계략" -> ctx.copy(successMultiplier = ctx.successMultiplier + 0.1)
                else -> ctx
            }
        },
        "che_환술" to object : ActionModifier {
            override val code = "che_환술"; override val name = "환술"
            override fun onCalcStat(stat: StatContext) = stat.copy(
                magicSuccessProb = stat.magicSuccessProb + 0.1,
                magicSuccessDamage = stat.magicSuccessDamage * 1.3
            )
        },
        "che_집중" to object : ActionModifier {
            override val code = "che_집중"; override val name = "집중"
            override fun onCalcStat(stat: StatContext) = stat.copy(
                magicSuccessDamage = stat.magicSuccessDamage * 1.5
            )
        },
        "che_신중" to object : ActionModifier {
            override val code = "che_신중"; override val name = "신중"
            override fun onCalcStat(stat: StatContext) = stat.copy(magicSuccessProb = stat.magicSuccessProb + 1.0)
        },
        "che_반계" to object : ActionModifier {
            override val code = "che_반계"; override val name = "반계"
            override fun onCalcOpposeStat(stat: StatContext) = stat.copy(
                magicSuccessProb = stat.magicSuccessProb - 0.1
            )
        },
        "che_보병" to object : ActionModifier {
            override val code = "che_보병"; override val name = "보병"
            override fun onCalcStat(stat: StatContext) = stat.copy(dexMultiplier = stat.dexMultiplier * 1.1)
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("징병", "모병") -> ctx.copy(costMultiplier = ctx.costMultiplier * 0.9)
                else -> ctx
            }
            override fun onCalcOpposeStat(stat: StatContext): StatContext {
                val opponentType = stat.opponentCrewType.toIntOrNull()?.let { CrewType.fromCode(it) } ?: return stat
                return when (opponentType.armType) {
                    ArmType.FOOTMAN -> stat.copy(warPower = stat.warPower * 0.9)
                    ArmType.CAVALRY -> stat.copy(warPower = stat.warPower * 0.8)
                    else -> stat
                }
            }
        },
        "che_궁병" to object : ActionModifier {
            override val code = "che_궁병"; override val name = "궁병"
            override fun onCalcStat(stat: StatContext) = stat.copy(
                dodgeChance = stat.dodgeChance + 0.2,
                dexMultiplier = stat.dexMultiplier * 1.1
            )
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("징병", "모병") -> ctx.copy(costMultiplier = ctx.costMultiplier * 0.9)
                else -> ctx
            }
        },
        "che_기병" to object : ActionModifier {
            override val code = "che_기병"; override val name = "기병"
            override fun onCalcStat(stat: StatContext) = stat.copy(
                warPower = stat.warPower * 1.15,
                dexMultiplier = stat.dexMultiplier * 1.1
            )
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("징병", "모병") -> ctx.copy(costMultiplier = ctx.costMultiplier * 0.9)
                else -> ctx
            }
        },
        "che_공성" to object : ActionModifier {
            override val code = "che_공성"; override val name = "공성"
            override fun onCalcStat(stat: StatContext) = stat.copy(dexMultiplier = stat.dexMultiplier * 1.1)
            override fun onCalcDomestic(ctx: DomesticContext) = when (ctx.actionCode) {
                in listOf("징병", "모병") -> ctx.copy(costMultiplier = ctx.costMultiplier * 0.9)
                else -> ctx
            }
        },
        "che_돌격" to object : ActionModifier {
            override val code = "che_돌격"; override val name = "돌격"
            override fun onCalcStat(stat: StatContext) = stat.copy(initWarPhase = stat.initWarPhase + 2.0)
        },
        "che_무쌍" to object : ActionModifier {
            override val code = "che_무쌍"; override val name = "무쌍"
            override fun onCalcStat(stat: StatContext): StatContext {
                val killnum = 0.0
                val logVal = log2(maxOf(1.0, killnum / 5.0))
                val attackMultiplier = 1.05 + logVal / 20.0
                val defenceMultiplier = 0.98 - logVal / 50.0
                return stat.copy(
                    criticalChance = stat.criticalChance + 0.1,
                    warPower = stat.warPower * attackMultiplier,
                    dodgeChance = stat.dodgeChance - (1.0 - defenceMultiplier)
                )
            }
        },
        "che_견고" to object : ActionModifier {
            override val code = "che_견고"; override val name = "견고"
            override fun onCalcOpposeStat(stat: StatContext) = stat.copy(
                criticalChance = stat.criticalChance - 0.2,
                magicSuccessProb = stat.magicSuccessProb - 0.1
            )
        },
        "che_위압" to object : ActionModifier {
            override val code = "che_위압"; override val name = "위압"
        },
        "che_저격" to object : ActionModifier {
            override val code = "che_저격"; override val name = "저격"
        },
        "che_필살" to object : ActionModifier {
            override val code = "che_필살"; override val name = "필살"
            override fun onCalcStat(stat: StatContext) = stat.copy(criticalChance = stat.criticalChance + 0.3)
        },
        "che_징병" to object : ActionModifier {
            override val code = "che_징병"; override val name = "징병"
            override fun onCalcStat(stat: StatContext) = stat.copy(leadership = stat.leadership * 1.25)
        },
        "che_의술" to object : ActionModifier {
            override val code = "che_의술"; override val name = "의술"
        },
        "che_격노" to object : ActionModifier {
            override val code = "che_격노"; override val name = "격노"
        },
        "che_척사" to object : ActionModifier {
            override val code = "che_척사"; override val name = "척사"
            override fun onCalcStat(stat: StatContext): StatContext {
                if (!isRegionalOrCityCrewType(stat.opponentCrewType)) {
                    return stat
                }
                return stat.copy(warPower = stat.warPower * 1.2)
            }
        },
    )

    fun get(code: String): ActionModifier? = specials[code]
    fun getAll(): Map<String, ActionModifier> = specials
}
