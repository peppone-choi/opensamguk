package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_불가침수락(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "불가침 수락"

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), OccupiedCity(), SuppliedCity(),
        ExistsDestNation(), ExistsDestGeneral()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val destNationName = destNation?.name ?: "알 수 없음"
        val year = (arg?.get("year") as? Number)?.toInt() ?: env.year
        val month = (arg?.get("month") as? Number)?.toInt() ?: env.month
        pushLog("<D><b>$destNationName</b></>와 <C>$year</>년 <C>$month</>월까지 불가침에 성공했습니다.")
        return CommandResult(true, logs)
    }
}
