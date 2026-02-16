package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_기술연구(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "기술 연구"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                NotWanderingNation(),
                OccupiedCity(),
                SuppliedCity(),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice)
            )
        }

    override fun getCost() = CommandCost(gold = env.develCost)
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val intel = general.intel.toInt()
        val trust = maxOf(50, city?.trust ?: 50)

        var score = (intel * (trust / 100.0) * (0.8 + rng.nextDouble() * 0.4)).toInt()
        score = maxOf(1, score)

        val successRatio = minOf(1.0, 0.1 * (trust / 80.0))
        val failRatio = minOf(1.0 - successRatio, 0.1)

        val roll = rng.nextDouble()
        val pick = when {
            roll < failRatio -> "fail"
            roll < failRatio + successRatio -> "success"
            else -> "normal"
        }

        when (pick) {
            "success" -> score = (score * 1.5).toInt()
            "fail" -> score = (score * 0.5).toInt()
        }
        score = maxOf(1, score)

        val logMessage = when (pick) {
            "fail" -> "${actionName}을(를) 실패하여 $score 상승했습니다. $date"
            "success" -> "${actionName}을(를) 성공하여 $score 상승했습니다. $date"
            else -> "${actionName}을(를) 하여 $score 상승했습니다. $date"
        }
        pushLog(logMessage)

        val exp = (score * 0.7).toInt()
        val ded = score
        val cost = getCost()

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-cost.gold},"experience":$exp,"dedication":$ded,"intelExp":1},"nationChanges":{"tech":$score},"criticalResult":"$pick"}"""
        )
    }
}
