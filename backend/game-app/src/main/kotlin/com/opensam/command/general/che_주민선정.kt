package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

private const val DEVELOP_COST_MULTIPLIER = 2
private const val SCORE_DIVISOR = 10.0
private const val MAX_TRUST = 100
private const val EXP_RATE = 0.7
private const val DED_RATE = 1.0

class che_주민선정(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "주민 선정"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                NotWanderingNation(),
                OccupiedCity(),
                SuppliedCity(),
                ReqGeneralRice(cost.rice),
                RemainCityTrust(MAX_TRUST)
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
        val trustIncrease = score / SCORE_DIVISOR
        val trustText = String.format("%.1f", trustIncrease)
        val exp = (score * EXP_RATE).roundToInt()
        val ded = (score * DED_RATE).roundToInt()

        // max_domestic_critical tracking (legacy parity)
        val maxDomesticCritical = if (pick == "success") trustIncrease else 0.0

        val logMessage = when (pick) {
            "fail" -> "주민선정을 <span class='ev_failed'>실패</span>하여 민심이 <C>${trustText}</> 상승했습니다. <1>$date</>"
            "success" -> "주민선정을 <S>성공</>하여 민심이 <C>${trustText}</> 상승했습니다. <1>$date</>"
            else -> "주민선정을 하여 민심이 <C>${trustText}</> 상승했습니다. <1>$date</>"
        }
        pushLog(logMessage)

        val cost = getCost()

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"leadershipExp":1,"max_domestic_critical":${String.format("%.2f", maxDomesticCritical)}},"cityChanges":{"trust":${String.format("%.2f", trustIncrease)},"trustMax":$MAX_TRUST},"criticalResult":"$pick"}"""
        )
    }

    private fun getDomesticExpLevelBonus(expLevel: Int): Double {
        return 1.0 + expLevel * 0.05
    }
}
