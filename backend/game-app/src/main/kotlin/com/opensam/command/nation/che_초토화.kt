package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val POST_REQ_TURN = 24

class che_초토화(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "초토화"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), OccupiedDestCity(), BeChief(),
        SuppliedCity(), SuppliedDestCity()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 2
    override fun getPostReqTurn() = POST_REQ_TURN

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destCityName = destCity?.name ?: "알 수 없음"
        pushLog("<G><b>$destCityName</b></>을 $actionName 했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
