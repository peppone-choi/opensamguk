package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private const val SABOTAGE_DEFAULT_PROB = 0.2
private const val MAX_SUCCESS_PROB = 0.5
private const val INJURY_MAX = 80
private const val INJURY_PROB_DEFAULT = 0.3

open class 화계(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "화계"

    protected open val statType: String = "intel"
    protected open val injuryGeneral: Boolean = true

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                OccupiedCity(),
                SuppliedCity(),
                NotOccupiedDestCity(),
                NotNeutralDestCity(),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
                DisallowDiplomacyBetweenStatus(mapOf(7 to "불가침국입니다.")),
            )
        }

    override val minConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                OccupiedCity(),
                SuppliedCity(),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
            )
        }

    override fun getCost(): CommandCost {
        val cost = env.develCost * 5
        return CommandCost(gold = cost, rice = cost)
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    protected fun getStatScore(): Int = when (statType) {
        "leadership" -> general.leadership.toInt()
        "strength" -> general.strength.toInt()
        else -> general.intel.toInt()
    }

    protected fun getStatExpKey(): String = when (statType) {
        "leadership" -> "leadershipExp"
        "strength" -> "strengthExp"
        else -> "intelExp"
    }

    /**
     * Calculate attack success probability based on attacker stat.
     * Legacy: genScore / sabotageProbCoefByStat (+ onCalcDomestic modifier)
     */
    protected fun calcAttackProb(): Double {
        return getStatScore() / env.sabotageProbCoefByStat.toDouble()
    }

    /**
     * Calculate defence probability based on dest city generals.
     * Legacy factors:
     * - max stat of defending generals / sabotageProbCoefByStat
     * - log2(affectCount + 1) - 1.25 * sabotageDefenceCoefByGeneralCnt
     * - city secu / secu_max / 5 (up to 20%p)
     * - supplied city: +0.1
     */
    protected fun calcDefenceProb(): Double {
        val dc = destCity ?: return 0.0
        val destNationId = dc.nationId
        val defenders = destCityGenerals ?: emptyList()

        var maxStat = 0
        var affectCount = 0

        for (defender in defenders) {
            if (defender.nationId != destNationId) continue
            affectCount++
            val defStat = when (statType) {
                "leadership" -> defender.leadership.toInt()
                "strength" -> defender.strength.toInt()
                else -> defender.intel.toInt()
            }
            maxStat = max(maxStat, defStat)
        }

        var prob = maxStat / env.sabotageProbCoefByStat.toDouble()
        prob += (ln((affectCount + 1).toDouble()) / ln(2.0) - 1.25) * env.sabotageDefenceCoefByGeneralCnt
        prob += if (dc.secuMax > 0) dc.secu.toDouble() / dc.secuMax / 5.0 else 0.0
        prob += if (dc.supplyState > 0) 0.1 else 0.0

        return prob
    }

    protected open fun affectDestCity(rng: Random, injuryCount: Int): Map<String, Int> {
        val dc = destCity!!
        val agriAmount = min(
            max(rng.nextInt(env.sabotageDamageMin, env.sabotageDamageMax + 1), 0),
            dc.agri
        )
        val commAmount = min(
            max(rng.nextInt(env.sabotageDamageMin, env.sabotageDamageMax + 1), 0),
            dc.comm
        )

        pushLog("<G><b>${dc.name}</b></>${josa(dc.name, "이")} 불타고 있습니다.")
        pushLog("<G><b>${dc.name}</b></>에 ${actionName}${josa(actionName, "이")} 성공했습니다. <1>${formatDate()}</>")
        pushLog("도시의 농업이 <C>${agriAmount}</>, 상업이 <C>${commAmount}</>만큼 감소하고, 장수 <C>${injuryCount}</>명이 부상 당했습니다.")

        return mapOf("agri" to -agriAmount, "comm" to -commAmount)
    }

    /**
     * Calculate injury to dest city generals (legacy: SabotageInjury).
     * Returns: pair of (injuryCount, list of injury effect maps)
     */
    protected fun calculateInjuries(rng: Random): Pair<Int, List<Map<String, Any>>> {
        if (!injuryGeneral) return Pair(0, emptyList())

        val dc = destCity ?: return Pair(0, emptyList())
        val defenders = destCityGenerals ?: emptyList()
        val injuries = mutableListOf<Map<String, Any>>()

        for (defender in defenders) {
            if (defender.nationId != dc.nationId) continue
            if (rng.nextDouble() >= INJURY_PROB_DEFAULT) continue

            val injuryAmount = rng.nextInt(1, 17) // 1-16
            val newInjury = min(defender.injury.toInt() + injuryAmount, INJURY_MAX)
            val newCrew = (defender.crew * 0.98).toInt()
            val newTrain = (defender.train * 0.98).toInt()

            injuries.add(mapOf(
                "generalId" to defender.id,
                "injury" to newInjury,
                "crew" to newCrew,
                "train" to newTrain
            ))
        }

        return Pair(injuries.size, injuries)
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dc = destCity!!
        val destCityName = dc.name

        // Distance factor (legacy: searchDistance, default 99 if not found)
        val dist = getDistanceTo(dc.id) ?: 99

        val attackProb = calcAttackProb()
        val defenceProb = calcDefenceProb()
        var prob = SABOTAGE_DEFAULT_PROB + attackProb - defenceProb
        prob /= dist
        prob = max(0.0, min(MAX_SUCCESS_PROB, prob))

        if (rng.nextDouble() >= prob) {
            pushLog("<G><b>${destCityName}</b></>에 ${actionName}${josa(actionName, "이")} 실패했습니다. <1>$date</>")

            val exp = rng.nextInt(1, 101)
            val ded = rng.nextInt(1, 71)
            val cost = getCost()

            return CommandResult(
                success = false,
                logs = logs,
                message = """{"statChanges":{"gold":${-cost.gold},"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"${getStatExpKey()}":1}}"""
            )
        }

        // Calculate injuries to defending generals
        val (injuryCount, injuryEffects) = calculateInjuries(rng)

        val cityChanges = affectDestCity(rng, injuryCount)

        val exp = rng.nextInt(201, 301)
        val ded = rng.nextInt(141, 211)
        val cost = getCost()

        // Filter out internal keys (starting with _) for city changes
        val publicCityChanges = cityChanges.filterKeys { !it.startsWith("_") }
        val cityJson = publicCityChanges.entries.joinToString(",") { "\"${it.key}\":${it.value}" }

        // Include extra metadata from affectDestCity (탈취 etc.)
        val extraJson = cityChanges.filterKeys { it.startsWith("_") }
            .entries.joinToString(",") { "\"${it.key.removePrefix("_")}\":${it.value}" }

        val injuryJson = if (injuryEffects.isNotEmpty()) {
            ",\"injuries\":[${injuryEffects.joinToString(",") { e ->
                """{"generalId":${e["generalId"]},"injury":${e["injury"]},"crew":${e["crew"]},"train":${e["train"]}}"""
            }}]"
        } else ""

        return CommandResult(
            success = true,
            logs = logs,
            message = buildString {
                append("""{"statChanges":{"gold":${-cost.gold},"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"${getStatExpKey()}":1,"firenum":1}""")
                append(""","destCityChanges":{"cityId":${dc.id},"state":32""")
                if (publicCityChanges.isNotEmpty()) append(",$cityJson")
                append("}")
                if (extraJson.isNotEmpty()) append(",\"extra\":{$extraJson}")
                append(injuryJson)
                append("}")
            }
        )
    }
}
