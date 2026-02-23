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

private const val DEVELOP_COST_MULTIPLIER = 2
private const val SCORE_MULTIPLIER = 10
private const val EXP_RATE = 0.7
private const val DED_RATE = 1.0

class che_정착장려(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "정착 장려"

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
        val reqRice = (env.develCost * DEVELOP_COST_MULTIPLIER)
        return CommandCost(rice = reqRice)
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val leadership = general.leadership.toInt()

        // Base score with explevel bonus (legacy: getDomesticExpLevelBonus)
        val expLevel = general.expLevel
        val expLevelBonus = getDomesticExpLevelBonus(expLevel)
        var baseScore = leadership * expLevelBonus * (0.8 + rng.nextDouble() * 0.4)

        // Critical ratio (legacy: CriticalRatioDomestic)
        val criticalRoll = rng.nextDouble()
        val pick: String
        val critMultiplier: Double
        when {
            criticalRoll < 0.15 -> { pick = "fail"; critMultiplier = 0.5 }
            criticalRoll > 0.85 -> { pick = "success"; critMultiplier = 1.5 }
            else -> { pick = "normal"; critMultiplier = 1.0 }
        }

        val score = max(1.0, baseScore * critMultiplier)
        val exp = (score * EXP_RATE).roundToInt()
        val ded = (score * DED_RATE).roundToInt()
        val popIncrease = (score * SCORE_MULTIPLIER).roundToInt()

        val scoreText = String.format("%,d", popIncrease)

        val logMessage = when (pick) {
            "fail" -> "정착장려를 <span class='ev_failed'>실패</span>하여 주민이 <C>${scoreText}</>명 증가했습니다. <1>$date</>"
            "success" -> "정착장려를 <S>성공</>하여 주민이 <C>${scoreText}</>명 증가했습니다. <1>$date</>"
            else -> "정착장려를 하여 주민이 <C>${scoreText}</>명 증가했습니다. <1>$date</>"
        }
        pushLog(logMessage)

        val cost = getCost()

        // max_domestic_critical tracking (legacy parity)
        val maxDomesticCritical = if (pick == "success") popIncrease else 0

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"leadershipExp":1,"max_domestic_critical":$maxDomesticCritical},"cityChanges":{"pop":$popIncrease,"popMax":true},"criticalResult":"$pick"}"""
        )
    }

    private fun getDomesticExpLevelBonus(expLevel: Int): Double {
        return 1.0 + expLevel * 0.05
    }
}
