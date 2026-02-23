package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.pow
import kotlin.random.Random

class che_천도(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "천도"

    private fun getDistance(): Int {
        // Distance should be calculated from current capital to destCity via BFS
        // For now use arg-provided distance or default to map calculation
        return (arg?.get("distance") as? Number)?.toInt() ?: 2
    }

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCostAmount()
            val baseGold = (env.gameStor["baseGold"] as? Number)?.toInt() ?: 1000
            val baseRice = (env.gameStor["baseRice"] as? Number)?.toInt() ?: 1000
            return listOf(
                OccupiedCity(),
                OccupiedDestCity(),
                BeChief(),
                SuppliedCity(),
                SuppliedDestCity(),
                ReqNationGold(baseGold + cost),
                ReqNationRice(baseRice + cost),
            )
        }

    private fun getCostAmount(): Int {
        val dist = getDistance()
        return (env.develCost * 5 * 2.0.pow(dist)).toInt()
    }

    override fun getCost(): CommandCost {
        val amount = getCostAmount()
        return CommandCost(gold = amount, rice = amount)
    }

    override fun getPreReqTurn() = getDistance() * 2
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dCity = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")

        // Check not already capital
        if (n.capitalCityId == dCity.id) {
            return CommandResult(false, logs, "이미 수도입니다.")
        }

        val cost = getCost()
        n.gold -= cost.gold
        n.rice -= cost.rice
        n.capitalCityId = dCity.id

        val expDed = 5 * (getPreReqTurn() + 1)
        general.experience += expDed
        general.dedication += expDed

        val josaRo = if (dCity.name.last().code % 28 != 0) "으로" else "로"
        pushLog("<G><b>${dCity.name}</b></>${josaRo} 천도했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
