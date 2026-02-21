package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_선전포고(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "선전포고"

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), OccupiedCity(), SuppliedCity(),
        BeOpeningPart(env.startYear + 1),
        ExistsDestNation()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, listOf("국가 정보를 찾을 수 없습니다"))
        val dn = destNation ?: return CommandResult(false, listOf("대상 국가 정보를 찾을 수 없습니다"))
        services!!.diplomacyService.declareWar(env.worldId, n.id, dn.id)
        general.experience += 50
        general.dedication += 50
        pushLog("<D><b>${dn.name}</b></>에 선전 포고 했습니다.<1>${formatDate()}</>")
        return CommandResult(true, logs)
    }
}
