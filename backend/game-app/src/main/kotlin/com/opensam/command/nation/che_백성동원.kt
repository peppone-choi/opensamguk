package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val STRATEGIC_GLOBAL_DELAY = 9

class che_백성동원(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "백성동원"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), OccupiedDestCity(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dCity = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")
        val date = formatDate()

        // Set strategic command cooldown
        n.strategicCmdLimit = STRATEGIC_GLOBAL_DELAY.toShort()

        dCity.pop = (dCity.pop * 0.2).toInt()

        general.experience += 5
        general.dedication += 5

        services?.generalRepository?.save(general)
        services?.generalRepository?.save(general)

        pushLog("백성동원 발동! <1>$date</>")
        return CommandResult(true, logs)
    }
}
