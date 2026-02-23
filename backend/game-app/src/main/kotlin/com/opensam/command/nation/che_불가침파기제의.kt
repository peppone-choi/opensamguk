package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.random.Random

class che_불가침파기제의(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "불가침 파기 제의"

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), OccupiedCity(), SuppliedCity(),
        ExistsDestNation(),
        AllowDiplomacyBetweenStatus(listOf(7), "불가침 중인 상대국에게만 가능합니다.")
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, listOf("국가 정보를 찾을 수 없습니다"))
        val dn = destNation ?: return CommandResult(false, listOf("대상 국가 정보를 찾을 수 없습니다"))

        val josaRo = JosaUtil.pick(dn.name, "로")
        pushLog("<D><b>${dn.name}</b></>${josaRo} 불가침 파기 제의 서신을 보냈습니다.<1>${formatDate()}</>")

        services!!.diplomacyService.sendDiplomaticMessage(
            worldId = env.worldId,
            srcNationId = n.id,
            destNationId = dn.id,
            srcGeneralId = general.id,
            action = "cancel_non_aggression",
            extra = mapOf("deletable" to false)
        )

        return CommandResult(true, logs)
    }
}
