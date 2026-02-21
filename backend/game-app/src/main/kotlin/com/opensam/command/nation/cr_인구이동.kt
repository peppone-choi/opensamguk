package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val DEVEL_COST = 100
private const val AMOUNT_LIMIT = 100000
private const val MIN_AVAILABLE_RECRUIT_POP = 10000

class cr_인구이동(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "인구이동"

    override val fullConditionConstraints = listOf(
        NotSameDestCity(), OccupiedCity(), OccupiedDestCity(),
        NearCity(1), BeChief(), SuppliedCity(), SuppliedDestCity()
    )

    override fun getCost(): CommandCost {
        val amount = (arg?.get("amount") as? Number)?.toInt() ?: 0
        val cost = (DEVEL_COST * amount) / 10000
        return CommandCost(gold = cost, rice = cost)
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dCity = destCity
        val destCityName = dCity?.name ?: "알 수 없음"
        val amount = ((arg?.get("amount") as? Number)?.toInt() ?: 0).coerceIn(0, AMOUNT_LIMIT)
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val c = city ?: return CommandResult(false, logs, "도시 정보를 찾을 수 없습니다")
        val dc = dCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")
        val cost = getCost()
        n.gold -= cost.gold
        n.rice -= cost.rice
        val actualAmount = amount.coerceAtMost(c.pop - MIN_AVAILABLE_RECRUIT_POP).coerceAtLeast(0)
        if (actualAmount <= 0) {
            return CommandResult(false, logs, "이동할 인구가 부족합니다")
        }
        c.pop -= actualAmount
        dc.pop = (dc.pop + actualAmount).coerceAtMost(dc.popMax)
        pushLog("<G><b>$destCityName</b></>로 인구 <C>$actualAmount</>명을 옮겼습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
