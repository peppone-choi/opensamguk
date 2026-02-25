package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.random.Random

class 귀환(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "귀환"

    override val fullConditionConstraints: List<Constraint> = listOf(
        NotBeNeutral(),
        NotWanderingNation(),
        NotCapital(checkCurrentCity = true),
    )

    override val minConditionConstraints: List<Constraint> = listOf(
        NotBeNeutral(),
        NotWanderingNation(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val officerLevel = general.officerLevel.toInt()

        // Legacy parity: officer levels 2-4 return to their assigned city,
        // others return to capital
        val destCityId: Long
        val destCityName: String

        if (officerLevel in 2..4) {
            destCityId = general.officerCity.toLong()
            destCityName = services?.let { it.getCityName(destCityId) } ?: destCity?.name ?: "담당도시"
        } else {
            destCityId = nation?.capitalCityId ?: 0L
            destCityName = services?.let { it.getCityName(destCityId) } ?: "수도"
        }

        val josaRo = JosaUtil.pick(destCityName, "로")
        pushLog("<G><b>${destCityName}</b></>${josaRo} 귀환했습니다. <1>$date</>")

        val exp = 70
        val ded = 100

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"moveTo":"$destCityId","experience":$exp,"dedication":$ded,"leadershipExp":1}}"""
        )
    }
}
