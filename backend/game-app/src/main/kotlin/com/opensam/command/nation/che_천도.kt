package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.pow
import kotlin.random.Random

private const val DEVEL_COST = 100

class che_천도(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "천도"

    private val distance: Int = 2

    override val fullConditionConstraints = listOf(
        OccupiedCity(), OccupiedDestCity(), BeChief(),
        SuppliedCity(), SuppliedDestCity()
    )

    override fun getCost(): CommandCost {
        val amount = (DEVEL_COST * 5 * 2.0.pow(distance)).toInt()
        return CommandCost(gold = amount, rice = amount)
    }

    override fun getPreReqTurn() = distance * 2
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dCity = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val cost = getCost()
        n.gold -= cost.gold
        n.rice -= cost.rice
        n.capitalCityId = dCity.id
        pushLog("<G><b>${dCity.name}</b></>(으)로 천도했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
