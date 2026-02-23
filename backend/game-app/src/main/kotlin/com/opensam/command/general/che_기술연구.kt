package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
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
        val trust = maxOf(50F, city?.trust ?: 50F).toDouble()

        // Legacy: base score = stat * trust/100 * expLevelBonus * rng(0.8..1.2)
        var score = (intel * (trust / 100.0) * (0.8 + rng.nextDouble() * 0.4)).toInt()
        score = max(1, score)

        // Legacy: CriticalRatioDomestic based on stat key
        var successRatio = 0.1
        var failRatio = 0.1
        if (trust < 80) {
            successRatio *= trust / 80.0
        }
        successRatio = minOf(1.0, successRatio)
        failRatio = minOf(1.0 - successRatio, failRatio)

        val roll = rng.nextDouble()
        val pick = when {
            roll < failRatio -> "fail"
            roll < failRatio + successRatio -> "success"
            else -> "normal"
        }

        // Legacy: CriticalScoreEx multiplier
        when (pick) {
            "success" -> score = (score * 1.5).toInt()
            "fail" -> score = (score * 0.5).toInt()
        }
        score = max(1, score)

        val logMessage = when (pick) {
            "fail" -> "${actionName}을 <span class='ev_failed'>실패</span>하여 <C>$score</> 상승했습니다. <1>$date</>"
            "success" -> "${actionName}을 <S>성공</>하여 <C>$score</> 상승했습니다. <1>$date</>"
            else -> "${actionName}을 하여 <C>$score</> 상승했습니다. <1>$date</>"
        }
        pushLog(logMessage)

        // Legacy parity: TechLimit check - if tech exceeds limit, score /= 4
        val techLimitApplied = env.isTechLimited(nation?.tech?.toDouble() ?: 0.0)
        val techScore = if (techLimitApplied) score / 4 else score

        // Legacy parity: divide by gennum (nation general count, min initialNationGenLimit)
        val genNum = maxOf(env.initialNationGenLimit, (nation?.meta?.get("gennum") as? Number)?.toInt() ?: 1)
        val nationTechDelta = techScore.toDouble() / genNum

        val exp = (score * 0.7).toInt()
        val ded = score
        val cost = getCost()

        // Legacy parity: updateMaxDomesticCritical on success
        val maxCriticalJson = if (pick == "success") {
            ""","maxDomesticCritical":$score"""
        } else {
            ""","maxDomesticCritical":0"""
        }

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-cost.gold},"experience":$exp,"dedication":$ded,"intelExp":1},"nationChanges":{"tech":$nationTechDelta},"criticalResult":"$pick"$maxCriticalJson}"""
        )
    }
}
