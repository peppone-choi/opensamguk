package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val EXPAND_CITY_POP_INCREASE = 100000
private const val EXPAND_CITY_DEVEL_INCREASE = 2000
private const val EXPAND_CITY_WALL_INCREASE = 2000
private const val EXPAND_CITY_COST_COEF = 500
private const val EXPAND_CITY_DEFAULT_COST = 60000

class che_증축(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "증축"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCostAmount()
            val baseGold = (env.gameStor["baseGold"] as? Number)?.toInt() ?: 1000
            val baseRice = (env.gameStor["baseRice"] as? Number)?.toInt() ?: 1000
            return listOf(
                OccupiedCity(),
                BeChief(),
                SuppliedCity(),
                ReqNationGold(baseGold + cost),
                ReqNationRice(baseRice + cost),
            )
        }

    private fun getCostAmount(): Int {
        return env.develCost * EXPAND_CITY_COST_COEF + EXPAND_CITY_DEFAULT_COST
    }

    override fun getCost(): CommandCost {
        val amount = getCostAmount()
        return CommandCost(gold = amount, rice = amount)
    }

    override fun getPreReqTurn() = 5
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val capitalCityId = n.capitalCityId ?: return CommandResult(false, logs, "수도가 없습니다")

        // Find capital city - use destCity (should be set to capital by command executor)
        val c = destCity ?: return CommandResult(false, logs, "수도 도시 정보를 찾을 수 없습니다")

        if (c.level <= 3.toShort()) {
            return CommandResult(false, logs, "수진, 진, 관문에서는 불가능합니다.")
        }
        if (c.level >= 8.toShort()) {
            return CommandResult(false, logs, "더이상 증축할 수 없습니다.")
        }

        val date = formatDate()
        val cost = getCost()

        n.gold -= cost.gold
        n.rice -= cost.rice

        c.level = (c.level + 1).toShort()
        c.popMax += EXPAND_CITY_POP_INCREASE
        c.agriMax += EXPAND_CITY_DEVEL_INCREASE
        c.commMax += EXPAND_CITY_DEVEL_INCREASE
        c.secuMax += EXPAND_CITY_DEVEL_INCREASE
        c.defMax += EXPAND_CITY_WALL_INCREASE
        c.wallMax += EXPAND_CITY_WALL_INCREASE

        val expDed = 5 * (getPreReqTurn() + 1)
        general.experience += expDed
        general.dedication += expDed

        pushLog("<G><b>${c.name}</b></>을 증축했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
