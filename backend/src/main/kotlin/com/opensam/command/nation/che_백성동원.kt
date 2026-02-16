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

class che_백성동원(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "백성동원"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), OccupiedDestCity(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0

    override fun getPostReqTurn(): Int {
        val genCount = max(INITIAL_NATION_GEN_LIMIT, INITIAL_NATION_GEN_LIMIT)
        return (sqrt(genCount * 4.0) * 10).roundToInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dCity = destCity
        val destCityName = dCity?.name ?: "알 수 없음"
        pushLog("백성동원 발동! <1>$date</>")
        return CommandResult(true, logs)
    }
}
