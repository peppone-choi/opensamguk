package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

class che_정착장려(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "정착 장려"

    companion object {
        private const val ACTION_KEY = "인구"
        private const val STAT_KEY = "leadership"
        private const val DEVELOP_COST_MULTIPLIER = 2
        private const val SCORE_MULTIPLIER = 10
        private const val EXP_RATE = 0.7
        private const val DED_RATE = 1.0
    }

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                NotWanderingNation(),
                OccupiedCity(),
                SuppliedCity(),
                ReqGeneralRice(cost.rice),
                RemainCityCapacity("pop", "인구")
            )
        }

    override fun getCost(): CommandCost {
        var reqRice = (env.develCost * DEVELOP_COST_MULTIPLIER).toDouble()
        reqRice = DomesticUtils.applyModifier(services, general, nation, ACTION_KEY, "cost", reqRice)
        return CommandCost(rice = Math.round(reqRice).toInt())
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val leadership = general.leadership.toDouble()

        // Legacy: base score = leadership * getDomesticExpLevelBonus * rng(0.8..1.2)
        // Note: 정착장려 does NOT use trust/100 scaling
        var score = leadership *
            DomesticUtils.getDomesticExpLevelBonus(general.expLevel.toInt()) *
            (0.8 + rng.nextDouble() * 0.4)

        // Apply onCalcDomestic 'score' modifier
        score *= DomesticUtils.applyModifier(services, general, nation, ACTION_KEY, "score", 1.0)
        score = max(1.0, score)

        // Legacy: CriticalRatioDomestic (no trust < 80 reduction for 정착장려)
        var (successRatio, failRatio) = DomesticUtils.criticalRatioDomestic(general, STAT_KEY)

        // Apply onCalcDomestic 'success'/'fail' modifiers
        successRatio = DomesticUtils.applyModifier(services, general, nation, ACTION_KEY, "success", successRatio)
        failRatio = DomesticUtils.applyModifier(services, general, nation, ACTION_KEY, "fail", failRatio)

        successRatio = successRatio.coerceIn(0.0, 1.0)
        failRatio = failRatio.coerceIn(0.0, 1.0 - successRatio)
        val normalRatio = 1.0 - failRatio - successRatio

        val pick = DomesticUtils.choiceUsingWeight(rng, mapOf(
            "fail" to failRatio,
            "success" to successRatio,
            "normal" to normalRatio
        ))

        // Legacy: CriticalScoreEx
        score *= DomesticUtils.criticalScoreEx(rng, pick)
        score = Math.round(score).toDouble()

        val exp = (score * EXP_RATE).roundToInt()
        val ded = (score * DED_RATE).roundToInt()

        // Legacy: updateMaxDomesticCritical tracks pre-multiplied score
        val maxDomesticCritical = if (pick == "success") score.toInt() else 0

        // Legacy: score *= 10 for population increase
        val popIncrease = (score * SCORE_MULTIPLIER).roundToInt()
        val scoreText = String.format("%,d", popIncrease)

        val josaUl = pickJosa(actionName, "을")
        val logMessage = when (pick) {
            "fail" -> "${actionName}${josaUl} <span class='ev_failed'>실패</span>하여 주민이 <C>${scoreText}</>명 증가했습니다. <1>$date</>"
            "success" -> "${actionName}${josaUl} <S>성공</>하여 주민이 <C>${scoreText}</>명 증가했습니다. <1>$date</>"
            else -> "${actionName}${josaUl} 하여 주민이 <C>${scoreText}</>명 증가했습니다. <1>$date</>"
        }
        pushLog(logMessage)

        val cost = getCost()

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"leadershipExp":1,"max_domestic_critical":$maxDomesticCritical},"cityChanges":{"pop":$popIncrease,"popMax":true},"criticalResult":"$pick"}"""
        )
    }
}
