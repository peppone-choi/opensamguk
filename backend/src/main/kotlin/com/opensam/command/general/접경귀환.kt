package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 접경귀환(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "접경귀환"

    override val fullConditionConstraints: List<Constraint> = listOf(
        NotBeNeutral(),
        NotWanderingNation(),
        NotOccupiedCity(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        // TODO: find nearest occupied cities from map graph
        val nearestCities = emptyList<Long>()

        if (nearestCities.isEmpty()) {
            pushLog("3칸 이내에 아국 도시가 없습니다.")
            return CommandResult(success = false, logs = logs)
        }

        val destCityId = nearestCities[rng.nextInt(nearestCities.size)]
        pushLog("접경귀환했습니다.")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"city":$destCityId}}"""
        )
    }
}
