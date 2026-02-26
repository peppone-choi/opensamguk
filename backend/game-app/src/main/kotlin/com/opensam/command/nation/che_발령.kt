package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_발령(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "발령"

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), OccupiedCity(), SuppliedCity(),
        ExistsDestGeneral(), FriendlyDestGeneral(),
        OccupiedDestCity(), SuppliedDestCity()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destGen = destGeneral ?: return CommandResult(false, logs, "대상 장수 정보를 찾을 수 없습니다")
        val dCity = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")

        if (destGen.id == general.id) {
            return CommandResult(false, logs, "본인입니다")
        }

        destGen.cityId = dCity.id
        destGen.troopId = 0

        // Set last발령 meta (yearMonth value)
        val yearMonth = env.year * 12 + env.month - 1
        destGen.meta["last발령"] = yearMonth

        pushLog("<Y>${destGen.name}</>을 <G><b>${dCity.name}</b></>으로 발령했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
