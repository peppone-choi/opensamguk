package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_불가침제의(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "불가침 제의"

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), ExistsDestNation(), DifferentDestNation()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destNationName = destNation?.name ?: "알 수 없음"
        val year = (arg?.get("year") as? Number)?.toInt() ?: env.year
        val month = (arg?.get("month") as? Number)?.toInt() ?: env.month
        pushLog("<D><b>$destNationName</b></>로 불가침 제의 서신을 보냈습니다.<1>$date</>")
        return CommandResult(true, logs)
    }
}
