package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.random.Random

abstract class DomesticCommand(
    general: General, env: CommandEnv, arg: Map<String, Any>? = null
) : GeneralCommand(general, env, arg) {

    abstract val cityKey: String
    abstract val statKey: String

    /** The action key used for onCalcDomestic modifier matching (legacy: $actionKey). */
    abstract val actionKey: String

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
        val trust = maxOf(50.0, (city?.trust ?: 50F).toDouble())

        // Legacy: base score = stat * trust/100 * getDomesticExpLevelBonus * rng(0.8..1.2)
        var score = stat.toDouble() * (trust / 100.0) *
            DomesticUtils.getDomesticExpLevelBonus(general.expLevel.toInt()) *
            (0.8 + rng.nextDouble() * 0.4)

        // Apply onCalcDomestic 'score' modifier
        score *= DomesticUtils.applyModifier(services, general, nation, actionKey, "score", 1.0)
        score = max(1.0, score)

        // Legacy: CriticalRatioDomestic
        var (successRatio, failRatio) = DomesticUtils.criticalRatioDomestic(general, statKey)
        if (trust < 80) {
            successRatio *= trust / 80.0
        }

        // Apply onCalcDomestic 'success'/'fail' modifiers
        successRatio = DomesticUtils.applyModifier(services, general, nation, actionKey, "success", successRatio)
        failRatio = DomesticUtils.applyModifier(services, general, nation, actionKey, "fail", failRatio)

        successRatio = successRatio.coerceIn(0.0, 1.0)
        failRatio = failRatio.coerceIn(0.0, 1.0 - successRatio)
        val normalRatio = 1.0 - failRatio - successRatio

        // Legacy: $rng->choiceUsingWeight
        val pick = DomesticUtils.choiceUsingWeight(rng, mapOf(
            "fail" to failRatio,
            "success" to successRatio,
            "normal" to normalRatio
        ))

        // Legacy: CriticalScoreEx
        score *= DomesticUtils.criticalScoreEx(rng, pick)
        score = Math.round(score).toDouble()
        score = max(1.0, score)

        val scoreInt = score.toInt()
        val exp = (score * 0.7).toInt()
        val ded = scoreInt

        // Legacy parity: updateMaxDomesticCritical on success, reset on non-success
        val maxCriticalJson = if (pick == "success") {
            ""","maxDomesticCritical":$scoreInt"""
        } else {
            ""","maxDomesticCritical":0"""
        }

        val josaUl = pickJosa(actionName, "을")
        val logMessage = when (pick) {
            "fail" -> "${actionName}${josaUl} <span class='ev_failed'>실패</span>하여 <C>$scoreInt</> 상승했습니다. <1>$date</>"
            "success" -> "${actionName}${josaUl} <S>성공</>하여 <C>$scoreInt</> 상승했습니다. <1>$date</>"
            else -> "${actionName}${josaUl} 하여 <C>$scoreInt</> 상승했습니다. <1>$date</>"
        }
        pushLog(logMessage)

        // Legacy parity: front line debuff with capital scaling
        var finalScore = scoreInt
        val c = city
        if (c != null && (c.frontState.toInt() == 1 || c.frontState.toInt() == 3)) {
            var actualDebuff = debuffFront

            if (nation?.capitalCityId == c.id?.toLong()) {
                val relYear = env.year - env.startYear
                if (relYear < 25) {
                    val debuffScale = (maxOf(0, relYear - 5).coerceAtMost(20)) * 0.05
                    actualDebuff = (debuffScale * debuffFront) + (1 - debuffScale)
                }
            }

            finalScore = (finalScore * actualDebuff).toInt()
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
        val newValue = minOf(maxValue, currentValue + finalScore)
        val actualDelta = newValue - currentValue

        val statExpKey = "${statKey}Exp"

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-getCost().gold},"experience":$exp,"dedication":$ded,"$statExpKey":1},"cityChanges":{"$cityKey":$actualDelta},"criticalResult":"$pick"$maxCriticalJson}"""
        )
    }
}
