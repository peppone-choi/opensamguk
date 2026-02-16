package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class event_대검병연구(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "대검병 연구"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(),
        ReqNationGold(1000 + 50000), ReqNationRice(1000 + 50000)
    )

    override fun getCost() = CommandCost(gold = 50000, rice = 50000)
    override fun getPreReqTurn() = 11
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        pushLog("<M>$actionName</> 완료")
        return CommandResult(true, logs)
    }
}
