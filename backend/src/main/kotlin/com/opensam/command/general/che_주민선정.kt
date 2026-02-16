package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
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

        val baseScore = leadership * (0.8 + rng.nextDouble() * 0.4)
        val criticalRoll = rng.nextDouble()

        val pick: String
        val multiplier: Double
        when {
            criticalRoll < 0.15 -> { pick = "fail"; multiplier = 0.5 }
            criticalRoll > 0.85 -> { pick = "success"; multiplier = 1.5 }
            else -> { pick = "normal"; multiplier = 1.0 }
        }

        val score = (baseScore * multiplier / SCORE_DIVISOR)
        val scoreText = String.format("%.1f", score)
        val exp = (baseScore * multiplier * EXP_RATE).toInt()
        val ded = (baseScore * multiplier * DED_RATE).toInt()

        val logMessage = when (pick) {
            "fail" -> "주민 선정을 실패하여 $scoreText 상승했습니다. $date"
            "success" -> "주민 선정을 성공하여 $scoreText 상승했습니다. $date"
            else -> "주민 선정을 하여 $scoreText 상승했습니다. $date"
        }
        pushLog(logMessage)

        val cost = getCost()

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"leadershipExp":1},"cityChanges":{"trust":$score},"criticalResult":"$pick"}"""
        )
    }
}
