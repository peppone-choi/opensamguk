package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.random.Random

class che_불가침수락(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "불가침 수락"
    override val canDisplay = false
    override val isReservable = false

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), OccupiedCity(), SuppliedCity(),
        ExistsDestNation(), ExistsDestGeneral(),
        ReqDestNationGeneralMatch()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, listOf("국가 정보를 찾을 수 없습니다"))
        val dn = destNation ?: return CommandResult(false, listOf("대상 국가 정보를 찾을 수 없습니다"))
        val dg = destGeneral ?: return CommandResult(false, listOf("대상 장수 정보를 찾을 수 없습니다"))

        services!!.diplomacyService.acceptNonAggression(env.worldId, n.id, dn.id)

        val josaWaDest = JosaUtil.pick(dn.name, "와")
        pushLog("<D><b>${dn.name}</b></>${josaWaDest} 불가침에 성공했습니다.")
        pushHistoryLog("<D><b>${dn.name}</b></>${josaWaDest} 불가침 성공")

        val josaWaSrc = JosaUtil.pick(n.name, "와")
        pushDestGeneralLog("<D><b>${n.name}</b></>${josaWaSrc} 불가침에 성공했습니다.")
        pushDestGeneralHistoryLog("<D><b>${n.name}</b></>${josaWaSrc} 불가침 성공")

        general.experience += 50
        general.dedication += 50

        return CommandResult(true, logs)
    }
}
