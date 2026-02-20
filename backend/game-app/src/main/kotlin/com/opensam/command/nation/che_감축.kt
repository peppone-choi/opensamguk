package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val EXPAND_CITY_COST_COEF = 5
private const val EXPAND_CITY_DEFAULT_COST = 1000
private const val DEVEL_COST = 100

class che_감축(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "감축"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), SuppliedCity()
    )

    override fun getCost(): CommandCost {
        val amount = DEVEL_COST * EXPAND_CITY_COST_COEF + EXPAND_CITY_DEFAULT_COST / 2
        return CommandCost(gold = amount, rice = amount)
    }

    override fun getPreReqTurn() = 5
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val nationName = nation?.name ?: "알 수 없음"
        pushLog("<G><b>수도</b></>을 감축했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
