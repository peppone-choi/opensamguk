package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

private const val INITIAL_NATION_GEN_LIMIT = 10
private const val STRATEGIC_GLOBAL_DELAY = 9

class che_이호경식(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "이호경식"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), ExistsDestNation(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0

    override fun getPostReqTurn(): Int {
        val genCount = max(INITIAL_NATION_GEN_LIMIT, INITIAL_NATION_GEN_LIMIT)
        return (sqrt(genCount * 16.0) * 10).roundToInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destNationName = destNation?.name ?: "알 수 없음"
        pushLog("$actionName 발동! <1>$date</>")
        return CommandResult(true, logs)
    }
}
