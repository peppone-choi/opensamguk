package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_국호변경(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "국호변경"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), SuppliedCity()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val newName =
            (arg?.get("nationName") as? String)
                ?: (arg?.get("name") as? String)
                ?: "알 수 없음"
        val oldName = nation?.name ?: "알 수 없음"
        pushLog("국호를 <D><b>$newName</b></>(으)로 변경합니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
