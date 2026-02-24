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

    companion object {
        private const val ACTION_KEY = "기술"
        private const val STAT_KEY = "intel"
    }

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
        val intel = general.intel.toDouble()
        val trust = maxOf(50.0, (city?.trust ?: 50F).toDouble())

        // Legacy: base score = intel * trust/100 * getDomesticExpLevelBonus * rng(0.8..1.2)
        var score = intel * (trust / 100.0) *
            DomesticUtils.getDomesticExpLevelBonus(general.expLevel.toInt()) *
            (0.8 + rng.nextDouble() * 0.4)

        // Apply onCalcDomestic 'score' modifier
        score *= DomesticUtils.applyModifier(services, general, nation, ACTION_KEY, "score", 1.0)
        score = max(1.0, score)

        // Legacy: CriticalRatioDomestic with trust < 80 reduction
        var (successRatio, failRatio) = DomesticUtils.criticalRatioDomestic(general, STAT_KEY)
        if (trust < 80) {
            successRatio *= trust / 80.0
        }

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

        // Legacy: CriticalScoreEx multiplier
        score *= DomesticUtils.criticalScoreEx(rng, pick)
        score = Math.round(score).toDouble()
        score = max(1.0, score)

        val scoreInt = score.toInt()
        val exp = (score * 0.7).toInt()
        val ded = scoreInt

        val josaUl = pickJosa(actionName, "을")
        val logMessage = when (pick) {
            "fail" -> "${actionName}${josaUl} <span class='ev_failed'>실패</span>하여 <C>$scoreInt</> 상승했습니다. <1>$date</>"
            "success" -> "${actionName}${josaUl} <S>성공</>하여 <C>$scoreInt</> 상승했습니다. <1>$date</>"
            else -> "${actionName}${josaUl} 하여 <C>$scoreInt</> 상승했습니다. <1>$date</>"
        }
        pushLog(logMessage)

        // Legacy parity: TechLimit check - if tech exceeds limit, score /= 4
        val techLimitApplied = env.isTechLimited(nation?.tech?.toDouble() ?: 0.0)
        val techScore = if (techLimitApplied) scoreInt / 4 else scoreInt

        // Legacy parity: divide by gennum (nation general count, min initialNationGenLimit)
        val genNum = maxOf(env.initialNationGenLimit, (nation?.meta?.get("gennum") as? Number)?.toInt() ?: 1)
        val nationTechDelta = techScore.toDouble() / genNum

        val cost = getCost()

        // Legacy parity: updateMaxDomesticCritical on success
        val maxCriticalJson = if (pick == "success") {
            ""","maxDomesticCritical":$scoreInt"""
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
