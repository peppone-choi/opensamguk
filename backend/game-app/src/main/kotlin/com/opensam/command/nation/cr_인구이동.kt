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
        pushLog("<G><b>$destCityName</b></>로 인구 <C>$amount</>명을 옮겼습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
