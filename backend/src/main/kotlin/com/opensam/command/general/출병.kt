package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.roundToInt
import kotlin.random.Random

class 출병(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "출병"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val relYear = env.year - env.startYear
            val cost = getCost()
            return listOf(
                NotOpeningPart(relYear),
                NotSameDestCity(),
                NotBeNeutral(),
                OccupiedCity(),
                ReqGeneralCrew(),
                ReqGeneralRice(cost.rice),
                AllowWar(),
                HasRouteWithEnemy(),
            )
        }

    override val minConditionConstraints: List<Constraint>
        get() {
            val relYear = env.year - env.startYear
            val cost = getCost()
            return listOf(
                NotOpeningPart(relYear + 2),
                NotBeNeutral(),
                OccupiedCity(),
                ReqGeneralCrew(),
                ReqGeneralRice(cost.rice),
            )
        }

    override fun getCost(): CommandCost {
        val rice = (general.crew / 100.0).roundToInt()
        return CommandCost(gold = 0, rice = rice)
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destCityName = destCity?.name ?: "알 수 없음"

        pushLog("$destCityName(으)로 출병합니다. <1>$date</>")

        val cost = getCost()
        val dexGain = (general.crew / 100.0).roundToInt()

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"rice":${-cost.rice}},"dexChanges":{"crewType":${general.crewType},"amount":$dexGain},"battleTriggered":true,"targetCityId":"${destCity?.id ?: 0}"}"""
        )
    }
}
