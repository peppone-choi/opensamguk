package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.random.Random

class che_불가침파기수락(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "불가침 파기 수락"
    override val canDisplay = false
    override val isReservable = false

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), ExistsDestNation(), ExistsDestGeneral(),
        ReqDestNationGeneralMatch(),
        AllowDiplomacyBetweenStatus(listOf(7), "불가침 중인 상대국에게만 가능합니다.")
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, listOf("국가 정보를 찾을 수 없습니다"))
        val dn = destNation ?: return CommandResult(false, listOf("대상 국가 정보를 찾을 수 없습니다"))
        val dg = destGeneral ?: return CommandResult(false, listOf("대상 장수 정보를 찾을 수 없습니다"))

        services!!.diplomacyService.setDiplomacyState(env.worldId, n.id, dn.id, state = 2, term = 0)

        val generalName = general.name
        val josaYiGeneral = JosaUtil.pick(generalName, "이")
        val josaYiNation = JosaUtil.pick(n.name, "이")

        val josaWaDest = JosaUtil.pick(dn.name, "와")
        pushLog("<D><b>${dn.name}</b></>${josaWaDest}의 불가침을 파기했습니다.")
        pushHistoryLog("<D><b>${dn.name}</b></>${josaWaDest}의 불가침 파기 수락")

        pushGlobalActionLog("<Y>${generalName}</>${josaYiGeneral} <D><b>${dn.name}</b></>${josaWaDest}의 불가침 조약을 <M>파기</> 하였습니다.")
        pushGlobalHistoryLog("<Y><b>【파기】</b></><D><b>${n.name}</b></>${josaYiNation} <D><b>${dn.name}</b></>${josaWaDest}의 불가침 조약을 <M>파기</> 하였습니다.")

        val josaWaSrc = JosaUtil.pick(n.name, "와")
        pushDestGeneralLog("<D><b>${n.name}</b></>${josaWaSrc}의 불가침 파기에 성공했습니다.")
        pushDestGeneralHistoryLog("<D><b>${n.name}</b></>${josaWaSrc}의 불가침 파기 성공")

        return CommandResult(true, logs)
    }
}
