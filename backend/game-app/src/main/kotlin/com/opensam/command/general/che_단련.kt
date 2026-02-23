package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.roundToInt
import kotlin.random.Random

private const val DEFAULT_TRAIN_LOW = 40
private const val DEFAULT_ATMOS_LOW = 40
private const val SCORE_DIVISOR = 200000.0

class che_단련(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "단련"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                ReqGeneralCrew(),
                ReqGeneralStatValue({ it.train.toInt() }, "훈련", DEFAULT_TRAIN_LOW),
                ReqGeneralStatValue({ it.atmos.toInt() }, "사기", DEFAULT_ATMOS_LOW),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice)
            )
        }

    override fun getCost() = CommandCost(gold = env.develCost, rice = env.develCost)
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val crew = general.crew
        val train = general.train.toInt()
        val atmos = general.atmos.toInt()

        // Weighted choice matching PHP: success=0.34, normal=0.33, fail=0.33
        val criticalRoll = rng.nextDouble()
        val pick: String
        val multiplier: Int
        when {
            criticalRoll < 0.34 -> { pick = "success"; multiplier = 3 }
            criticalRoll < 0.67 -> { pick = "normal"; multiplier = 2 }
            else -> { pick = "fail"; multiplier = 1 }
        }

        val baseScore = (crew.toDouble() * train * atmos) / SCORE_DIVISOR
        val score = (baseScore * multiplier).roundToInt()
        val scoreText = "%,d".format(score)

        val armTypeName = getCrewTypeName(general.crewType.toInt()) ?: "병사"

        val logMessage = when (pick) {
            "fail" -> "단련이 <span class='ev_failed'>지지부진</span>하여 ${armTypeName} 숙련도가 <C>${scoreText}</> 향상되었습니다. <1>$date</>"
            "success" -> "단련이 <S>일취월장</>하여 ${armTypeName} 숙련도가 <C>${scoreText}</> 향상되었습니다. <1>$date</>"
            else -> "${armTypeName} 숙련도가 <C>${scoreText}</> 향상되었습니다. <1>$date</>"
        }
        pushLog(logMessage)

        val exp = crew / 400
        val cost = getCost()

        // random stat exp weighted by stats
        val leadership = general.leadership.toInt()
        val strength = general.strength.toInt()
        val intel = general.intel.toInt()
        val statWeights = listOf(
            "leadershipExp" to leadership,
            "strengthExp" to strength,
            "intelExp" to intel
        )
        val totalWeight = statWeights.sumOf { it.second }
        var roll = rng.nextDouble() * totalWeight
        var incStat = "leadershipExp"
        for ((key, weight) in statWeights) {
            roll -= weight
            if (roll < 0) { incStat = key; break }
        }

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-cost.gold},"rice":${-cost.rice},"experience":$exp,"$incStat":1},"dexChanges":{"crewType":${general.crewType},"amount":$score},"criticalResult":"$pick"}"""
        )
    }
}
