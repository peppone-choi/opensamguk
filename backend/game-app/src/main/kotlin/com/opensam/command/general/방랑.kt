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
                AllowDiplomacyStatus(general.nationId, listOf(2, 7), "방랑할 수 없는 외교상태입니다."),
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
        val nationName = nation?.name ?: "알 수 없음"
        val josaYi = pickJosa(generalName, "이")
        val josaUn = pickJosa(generalName, "은")
        val josaUl = pickJosa(nationName, "을")

        // Action log
        pushLog("영토를 버리고 방랑의 길을 떠납니다. <1>$date</>")
        // Global action log
        pushGlobalLog("<Y>${generalName}</>${josaYi} 방랑의 길을 떠납니다.")
        // Global history log
        pushGlobalLog("<R><b>【방랑】</b></><D><b>${generalName}</b></>${josaUn} <R>방랑</>의 길을 떠납니다.")
        // General history log
        pushHistoryLog("<D><b>${nationName}</b></>${josaUl} 버리고 방랑")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"becomeWanderer":true,"nationChanges":{"name":"$generalName","color":"#330000","level":0,"typeCode":"None","tech":0,"capitalCityId":null},"releaseAllCities":true,"resetDiplomacy":true,"allNationGenerals":{"makeLimit":12,"officerLevel":{"resetBelow":12},"officerCity":0}}"""
        )
    }
}
