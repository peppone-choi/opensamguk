package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.roundToInt
import kotlin.random.Random

private const val DEBUFF_FRONT = 0.5
private const val EXP_RATE = 0.7 / 3.0
private const val DED_RATE = 1.0 / 3.0

class che_물자조달(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "물자조달"

    override val fullConditionConstraints: List<Constraint>
        get() = listOf(
            NotBeNeutral(),
            NotWanderingNation(),
            OccupiedCity(),
            SuppliedCity()
        )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val leadership = general.leadership.toInt()
        val strength = general.strength.toInt()
        val intel = general.intel.toInt()

        val resourceType = if (rng.nextDouble() < 0.5) "gold" else "rice"
        val resName = if (resourceType == "gold") "금" else "쌀"

        var score = (leadership + strength + intel) * (0.8 + rng.nextDouble() * 0.4)

        val criticalRoll = rng.nextDouble()
        val pick: String
        val multiplier: Double
        when {
            criticalRoll < 0.3 -> { pick = "fail"; multiplier = 0.5 }
            criticalRoll > 0.9 -> { pick = "success"; multiplier = 1.5 }
            else -> { pick = "normal"; multiplier = 1.0 }
        }
        score *= multiplier

        val c = city
        if (c != null && (c.frontState.toInt() == 1 || c.frontState.toInt() == 3)) {
            score *= DEBUFF_FRONT
        }

        val finalScore = score.roundToInt()
        val exp = (finalScore * EXP_RATE).roundToInt()
        val ded = (finalScore * DED_RATE).roundToInt()

        val logMessage = when (pick) {
            "fail" -> "조달을 실패하여 ${resName}을 $finalScore 조달했습니다. $date"
            "success" -> "조달을 성공하여 ${resName}을 $finalScore 조달했습니다. $date"
            else -> "${resName}을 $finalScore 조달했습니다. $date"
        }
        pushLog(logMessage)

        // random stat exp weighted by stats
        val statWeights = listOf(
            "leadershipExp" to leadership,
            "strengthExp" to strength,
            "intelExp" to intel
        )
        val totalWeight = statWeights.sumOf { it.second }
        var roll = (rng.nextDouble() * totalWeight)
        var incStat = "leadershipExp"
        for ((key, weight) in statWeights) {
            roll -= weight
            if (roll < 0) { incStat = key; break }
        }

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"experience":$exp,"dedication":$ded,"$incStat":1},"nationChanges":{"$resourceType":$finalScore},"criticalResult":"$pick"}"""
        )
    }
}
