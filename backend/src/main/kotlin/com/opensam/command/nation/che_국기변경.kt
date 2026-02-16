package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_국기변경(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "국기변경"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), SuppliedCity()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val colorType = arg?.get("colorType") as? String ?: "red"
        pushLog("국기를 변경하였습니다 <1>$date</>")
        return CommandResult(true, logs)
    }
}
