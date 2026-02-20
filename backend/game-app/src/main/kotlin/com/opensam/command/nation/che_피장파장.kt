package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_피장파장(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "피장파장"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), ExistsDestNation(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 1
    override fun getPostReqTurn() = 8

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destNationName = destNation?.name ?: "알 수 없음"
        val targetCommandName = arg?.get("commandType") as? String ?: "전략"
        pushLog("<G><b>$targetCommandName</b></> 전략의 $actionName 발동! <1>$date</>")
        return CommandResult(true, logs)
    }
}
