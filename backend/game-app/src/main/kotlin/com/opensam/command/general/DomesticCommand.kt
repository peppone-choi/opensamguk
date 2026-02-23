package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

abstract class DomesticCommand(
    general: General, env: CommandEnv, arg: Map<String, Any>? = null
) : GeneralCommand(general, env, arg) {

    abstract val cityKey: String
    abstract val statKey: String
    open val debuffFront: Double = 0.5

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                NotWanderingNation(),
                OccupiedCity(),
                SuppliedCity(),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
                RemainCityCapacity(cityKey, actionName)
            )
        }

    override fun getCost() = CommandCost(gold = env.develCost)
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    protected fun getStat(): Int = when (statKey) {
        "leadership" -> general.leadership.toInt()
        "strength" -> general.strength.toInt()
        "intel" -> general.intel.toInt()
        "politics" -> general.politics.toInt()
        "charm" -> general.charm.toInt()
        else -> general.intel.toInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val stat = getStat()
        val trust = maxOf(50F, city?.trust ?: 50F).toDouble()

        // Legacy: base score = stat * trust/100 * getDomesticExpLevelBonus * rng(0.8..1.2)
        var score = (stat * (trust / 100.0) * (0.8 + rng.nextDouble() * 0.4)).toInt()
        score = max(1, score)

        // Legacy: CriticalRatioDomestic
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

        // Legacy: CriticalScoreEx
        when (pick) {
            "success" -> score = (score * 1.5).toInt()
            "fail" -> score = (score * 0.5).toInt()
        }
        score = max(1, score)

        // Legacy parity: updateMaxDomesticCritical on success, reset on non-success
        val maxCriticalJson = if (pick == "success") {
            ""","maxDomesticCritical":$score"""
        } else {
            ""","maxDomesticCritical":0"""
        }

        val logMessage = when (pick) {
            "fail" -> "${actionName}을 <span class='ev_failed'>실패</span>하여 <C>$score</> 상승했습니다. <1>$date</>"
            "success" -> "${actionName}을 <S>성공</>하여 <C>$score</> 상승했습니다. <1>$date</>"
            else -> "${actionName}을 하여 <C>$score</> 상승했습니다. <1>$date</>"
        }
        pushLog(logMessage)

        // Legacy parity: front line debuff with capital scaling
        val c = city
        if (c != null && (c.frontState.toInt() == 1 || c.frontState.toInt() == 3)) {
            var actualDebuff = debuffFront

            // Legacy: if capital and relYear < 25, scale debuff down
            if (nation?.capitalCityId == c.id?.toLong()) {
                val relYear = env.year - env.startYear
                if (relYear < 25) {
                    val debuffScale = (maxOf(0, relYear - 5).coerceAtMost(20)) * 0.05
                    actualDebuff = (debuffScale * debuffFront) + (1 - debuffScale)
                }
            }

            score = (score * actualDebuff).toInt()
        }

        val currentValue = when (cityKey) {
            "agri" -> c?.agri ?: 0
            "comm" -> c?.comm ?: 0
            "secu" -> c?.secu ?: 0
            "def" -> c?.def ?: 0
            "wall" -> c?.wall ?: 0
            else -> 0
        }
        val maxValue = when (cityKey) {
            "agri" -> c?.agriMax ?: 1000
            "comm" -> c?.commMax ?: 1000
            "secu" -> c?.secuMax ?: 1000
            "def" -> c?.defMax ?: 1000
            "wall" -> c?.wallMax ?: 1000
            else -> 1000
        }
        val newValue = minOf(maxValue, currentValue + score)
        val actualDelta = newValue - currentValue

        val exp = (score * 0.7).toInt()
        val ded = score

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-getCost().gold},"experience":$exp,"dedication":$ded,"${statKey}Exp":1},"cityChanges":{"$cityKey":$actualDelta},"criticalResult":"$pick"$maxCriticalJson}"""
        )
    }
}
