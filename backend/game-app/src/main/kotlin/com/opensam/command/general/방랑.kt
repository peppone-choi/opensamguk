package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 방랑(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "방랑"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val relYear = env.year - env.startYear
            return listOf(
                BeLord(),
                NotWanderingNation(),
                NotOpeningPart(relYear),
            )
        }

    override val minConditionConstraints: List<Constraint>
        get() {
            val relYear = env.year - env.startYear
            return listOf(
                BeLord(),
                NotWanderingNation(),
                NotOpeningPart(relYear),
            )
        }

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val generalName = general.name

        pushLog("영토를 버리고 방랑의 길을 떠납니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"becomeWanderer":true,"nationChanges":{"name":"$generalName","color":"#330000","level":0,"typeCode":"None","tech":0,"capitalCityId":null},"releaseAllCities":true,"resetDiplomacy":true,"allNationGenerals":{"makeLimit":12,"officerLevel":{"resetBelow":12},"officerCity":0},"globalLog":"${generalName}이(가) 방랑의 길을 떠납니다.","historyLog":"${generalName}은(는) 방랑의 길을 떠납니다."}"""
        )
    }
}
