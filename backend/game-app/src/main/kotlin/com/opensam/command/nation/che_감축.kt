package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.random.Random

private const val POP_INCREASE = 10000
private const val FIXED_COST = 500

class che_감축(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "감축"

    override val fullConditionConstraints = listOf(
        OccupiedCity(),
        BeChief(),
        SuppliedCity(),
        // Capital level > 4 and > original level checked in run()
    )

    override fun getCost(): CommandCost {
        return CommandCost(gold = FIXED_COST, rice = FIXED_COST)
    }

    override fun getPreReqTurn() = 5
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val capitalCity = city ?: run {
            val capitalCityId = n.capitalCityId
                ?: return CommandResult(false, logs, "방랑상태에서는 불가능합니다.")
            services?.cityRepository?.findById(capitalCityId)?.orElse(null)
                ?: return CommandResult(false, logs, "수도 정보를 찾을 수 없습니다")
        }
        val date = formatDate()

        if (capitalCity.level <= 4) {
            return CommandResult(false, logs, "더이상 감축할 수 없습니다.")
        }

        capitalCity.level = (capitalCity.level - 1).toShort()
        capitalCity.pop = max(capitalCity.pop - POP_INCREASE, 0)
        capitalCity.popMax -= POP_INCREASE

        val cost = getCost()
        n.gold -= cost.gold
        n.rice -= cost.rice

        // Increment capset (nation meta)
        val capset = (n.meta["capset"] as? Number)?.toInt() ?: 0
        n.meta["capset"] = capset + 1

        general.experience += 5 * (getPreReqTurn() + 1)
        general.dedication += 5 * (getPreReqTurn() + 1)

        pushLog("<G><b>${capitalCity.name}</b></>을 감축했습니다. <1>$date</>")

        return CommandResult(true, logs)
    }
}
