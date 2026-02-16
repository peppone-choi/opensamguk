package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 거병(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "거병"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val relYear = env.year - env.startYear
            return listOf(
                BeNeutral(),
                BeOpeningPart(relYear + 1),
                AllowJoinAction(),
            )
        }

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val generalName = general.name
        val cityName = city?.name ?: "알 수 없음"

        pushLog("거병에 성공하였습니다. <1>$date</>")
        pushLog("${generalName}이 ${cityName}에 거병하였습니다.")

        val exp = 100
        val ded = 100

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"experience":$exp,"dedication":$ded,"belong":1,"officerLevel":12,"officerCity":0},"nationChanges":{"createWanderingNation":true,"nationName":"$generalName"},"historyLog":{"global":"【거병】${generalName}이 세력을 결성하였습니다.","general":"${cityName}에서 거병","nation":"${generalName}이 ${cityName}에서 거병"}}"""
        )
    }
}
