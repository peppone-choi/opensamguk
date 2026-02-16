package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val BASE_GOLD = 1000
private const val BASE_RICE = 1000
private const val EXPAND_CITY_COST_COEF = 5
private const val EXPAND_CITY_DEFAULT_COST = 1000
private const val DEVEL_COST = 100

class che_증축(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "증축"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), SuppliedCity(),
        ReqNationGold(BASE_GOLD + DEVEL_COST * EXPAND_CITY_COST_COEF + EXPAND_CITY_DEFAULT_COST),
        ReqNationRice(BASE_RICE + DEVEL_COST * EXPAND_CITY_COST_COEF + EXPAND_CITY_DEFAULT_COST)
    )

    override fun getCost(): CommandCost {
        val amount = DEVEL_COST * EXPAND_CITY_COST_COEF + EXPAND_CITY_DEFAULT_COST
        return CommandCost(gold = amount, rice = amount)
    }

    override fun getPreReqTurn() = 5
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        pushLog("<G><b>수도</b></>을 증축했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
