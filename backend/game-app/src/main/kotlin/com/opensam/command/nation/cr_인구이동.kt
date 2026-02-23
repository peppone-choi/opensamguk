package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.math.round
import kotlin.random.Random

private const val AMOUNT_LIMIT = 100000
private const val MIN_AVAILABLE_RECRUIT_POP = 30000

class cr_인구이동(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "인구이동"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCostAmount()
            val baseGold = (env.gameStor["baseGold"] as? Number)?.toInt() ?: 1000
            val baseRice = (env.gameStor["baseRice"] as? Number)?.toInt() ?: 1000
            return listOf(
                NotSameDestCity(),
                OccupiedCity(),
                OccupiedDestCity(),
                NearCity(1),
                BeChief(),
                SuppliedCity(),
                SuppliedDestCity(),
                ReqNationGold(baseGold + cost),
                ReqNationRice(baseRice + cost),
            )
        }

    private fun getAmount(): Int {
        return ((arg?.get("amount") as? Number)?.toInt() ?: 0).coerceIn(0, AMOUNT_LIMIT)
    }

    private fun getCostAmount(): Int {
        val amount = getAmount()
        return round(env.develCost.toDouble() * amount / 10000).toInt()
    }

    override fun getCost(): CommandCost {
        val cost = getCostAmount()
        return CommandCost(gold = cost, rice = cost)
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val c = city ?: return CommandResult(false, logs, "도시 정보를 찾을 수 없습니다")
        val dc = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")

        val requestedAmount = getAmount()
        val actualAmount = requestedAmount.coerceAtMost(c.pop - MIN_AVAILABLE_RECRUIT_POP).coerceAtLeast(0)

        if (actualAmount <= 0) {
            return CommandResult(false, logs, "이동할 인구가 부족합니다")
        }

        val cost = CommandCost(
            gold = round(env.develCost.toDouble() * actualAmount / 10000).toInt(),
            rice = round(env.develCost.toDouble() * actualAmount / 10000).toInt()
        )

        c.pop -= actualAmount
        dc.pop += actualAmount
        n.gold -= cost.gold
        n.rice -= cost.rice

        general.experience += 5
        general.dedication += 5

        val amountText = String.format("%,d", actualAmount)
        pushLog("<G><b>${dc.name}</b></>로 인구 <C>$amountText</>명을 옮겼습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
