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

private const val SABOTAGE_PROB_COEF_BY_STAT = 500.0
private const val SABOTAGE_DEFAULT_PROB = 0.2
private const val SABOTAGE_DAMAGE_MIN = 200
private const val SABOTAGE_DAMAGE_MAX = 400

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
        val cost = 5 * 5
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

    protected open fun affectDestCity(rng: Random, injuryCount: Int): Map<String, Int> {
        val dc = destCity!!
        val agriAmount = min(rng.nextInt(SABOTAGE_DAMAGE_MIN, SABOTAGE_DAMAGE_MAX + 1), dc.agri)
        val commAmount = min(rng.nextInt(SABOTAGE_DAMAGE_MIN, SABOTAGE_DAMAGE_MAX + 1), dc.comm)

        pushLog("${dc.name} 불타고 있습니다.")
        pushLog("${dc.name}에 ${actionName}이 성공했습니다. <1>${formatDate()}</>")
        pushLog("도시의 농업이 ${agriAmount}, 상업이 ${commAmount}만큼 감소하고, 장수 ${injuryCount}명이 부상 당했습니다.")

        return mapOf("agri" to -agriAmount, "comm" to -commAmount)
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dc = destCity!!
        val destCityName = dc.name

        val attackProb = getStatScore() / SABOTAGE_PROB_COEF_BY_STAT
        var prob = SABOTAGE_DEFAULT_PROB + attackProb
        prob = max(0.0, min(0.5, prob))

        if (rng.nextDouble() >= prob) {
            pushLog("${destCityName}에 ${actionName}이 실패했습니다. <1>$date</>")

            val exp = rng.nextInt(1, 101)
            val ded = rng.nextInt(1, 71)
            val cost = getCost()

            return CommandResult(
                success = false,
                logs = logs,
                message = """{"statChanges":{"gold":${-cost.gold},"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"${getStatExpKey()}":1}}"""
            )
        }

        val injuryCount = 0
        val cityChanges = affectDestCity(rng, injuryCount)

        val exp = rng.nextInt(201, 301)
        val ded = rng.nextInt(141, 211)
        val cost = getCost()

        val cityJson = cityChanges.entries.joinToString(",") { "\"${it.key}\":${it.value}" }

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-cost.gold},"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"${getStatExpKey()}":1},"destCityChanges":{"cityId":${dc.id},$cityJson},"injuryCount":$injuryCount}"""
        )
    }
}
