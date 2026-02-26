package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val EXPAND_CITY_POP_INCREASE = 10000
private const val EXPAND_CITY_COST = 1500

class che_증축(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "증축"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = EXPAND_CITY_COST
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

    override fun getCost(): CommandCost {
        return CommandCost(gold = EXPAND_CITY_COST, rice = EXPAND_CITY_COST)
    }

    override fun getPreReqTurn() = 5
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val c = city ?: destCity ?: return CommandResult(false, logs, "수도 도시 정보를 찾을 수 없습니다")

        if (c.level >= 8.toShort()) {
            return CommandResult(false, logs, "더이상 증축할 수 없습니다.")
        }

        val date = formatDate()
        val cost = getCost()

        n.gold -= cost.gold
        n.rice -= cost.rice

        c.level = (c.level + 1).toShort()
        c.popMax += EXPAND_CITY_POP_INCREASE

        val expDed = 5 * (getPreReqTurn() + 1)
        general.experience += expDed
        general.dedication += expDed

        pushLog("<G><b>${c.name}</b></>을 증축했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
