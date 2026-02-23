package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.random.Random

private const val POP_INCREASE = 100000
private const val DEVEL_INCREASE = 2000
private const val WALL_INCREASE = 2000
private const val DEFAULT_COST = 60000
private const val COST_COEF = 500
private const val MIN_POP = 30000

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
        val amount = env.develCost * COST_COEF + DEFAULT_COST / 2
        return CommandCost(gold = amount, rice = amount)
    }

    override fun getPreReqTurn() = 5
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val capitalCityId = n.capitalCityId
            ?: return CommandResult(false, logs, "방랑상태에서는 불가능합니다.")
        val capitalCity = services!!.cityRepository.findById(capitalCityId).orElse(null)
            ?: return CommandResult(false, logs, "수도 정보를 찾을 수 없습니다")
        val date = formatDate()

        if (capitalCity.level <= 4) {
            return CommandResult(false, logs, "더이상 감축할 수 없습니다.")
        }

        // Decrease city level and stats by fixed amounts (matching PHP/TS)
        capitalCity.level = (capitalCity.level - 1).toShort()
        capitalCity.pop = max(capitalCity.pop - POP_INCREASE, MIN_POP)
        capitalCity.agri = max(capitalCity.agri - DEVEL_INCREASE, 0)
        capitalCity.comm = max(capitalCity.comm - DEVEL_INCREASE, 0)
        capitalCity.secu = max(capitalCity.secu - DEVEL_INCREASE, 0)
        capitalCity.def = max(capitalCity.def - WALL_INCREASE, 0)
        capitalCity.wall = max(capitalCity.wall - WALL_INCREASE, 0)
        capitalCity.popMax -= POP_INCREASE
        capitalCity.agriMax -= DEVEL_INCREASE
        capitalCity.commMax -= DEVEL_INCREASE
        capitalCity.secuMax -= DEVEL_INCREASE
        capitalCity.defMax -= WALL_INCREASE
        capitalCity.wallMax -= WALL_INCREASE

        // Recover cost to nation treasury (add, not subtract)
        val cost = getCost()
        n.gold += cost.gold
        n.rice += cost.rice

        // Increment capset (nation meta)
        val capset = (n.meta["capset"] as? Number)?.toInt() ?: 0
        n.meta["capset"] = capset + 1

        general.experience += 5 * (getPreReqTurn() + 1)
        general.dedication += 5 * (getPreReqTurn() + 1)

        pushLog("<G><b>${capitalCity.name}</b></>을 감축했습니다. <1>$date</>")

        return CommandResult(true, logs)
    }
}
