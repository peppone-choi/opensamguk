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
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val c = city ?: return CommandResult(false, logs, "도시 정보를 찾을 수 없습니다")
        val date = formatDate()
        val cost = getCost()
        n.gold -= cost.gold
        n.rice -= cost.rice
        c.level = (c.level + 1).toShort()
        val ratio = 1.25
        c.popMax = (c.popMax * ratio).toInt()
        c.agriMax = (c.agriMax * ratio).toInt()
        c.commMax = (c.commMax * ratio).toInt()
        c.secuMax = (c.secuMax * ratio).toInt()
        c.defMax = (c.defMax * ratio).toInt()
        c.wallMax = (c.wallMax * ratio).toInt()
        pushLog("<G><b>${c.name}</b></>을 증축했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
