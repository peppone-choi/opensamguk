package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.random.Random

class che_종전수락(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "종전 수락"
    override val canDisplay = false
    override val isReservable = false

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), ExistsDestNation(), ExistsDestGeneral(),
        ReqDestNationGeneralMatch(),
        AllowDiplomacyBetweenStatus(listOf(0, 1), "상대국과 선포, 전쟁중이지 않습니다.")
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, listOf("국가 정보를 찾을 수 없습니다"))
        val dn = destNation ?: return CommandResult(false, listOf("대상 국가 정보를 찾을 수 없습니다"))
        val dg = destGeneral ?: return CommandResult(false, listOf("대상 장수 정보를 찾을 수 없습니다"))

        val generalName = general.name
        val nationName = n.name
        val destNationName = dn.name

        val josaYiGeneral = JosaUtil.pick(generalName, "이")
        val josaYiNation = JosaUtil.pick(nationName, "이")

        // Update diplomacy: state=2 (neutral), term=0
        services!!.diplomacyService.setDiplomacyState(env.worldId, n.id, dn.id, state = 2, term = 0)

        // Update nation fronts
        services!!.nationService.setNationFront(env.worldId, n.id)
        services!!.nationService.setNationFront(env.worldId, dn.id)

        // General action + history logs
        val josaWaDest = JosaUtil.pick(destNationName, "와")
        pushLog("<D><b>${destNationName}</b></>${josaWaDest} 종전에 합의했습니다.")
        pushHistoryLog("<D><b>${destNationName}</b></>${josaWaDest} 종전 수락")

        // Global action + history
        pushGlobalActionLog("<Y>${generalName}</>${josaYiGeneral} <D><b>${destNationName}</b></>${josaWaDest} <M>종전 합의</> 하였습니다.")
        pushGlobalHistoryLog("<Y><b>【종전】</b></><D><b>${nationName}</b></>${josaYiNation} <D><b>${destNationName}</b></>${josaWaDest} <M>종전 합의</> 하였습니다.")

        // Own national history
        pushNationalHistoryLog("<D><b>${destNationName}</b></>${josaWaDest} 종전")

        // Dest general logs
        val josaWaSrc = JosaUtil.pick(nationName, "와")
        pushDestGeneralLog("<D><b>${nationName}</b></>${josaWaSrc} 종전에 성공했습니다.")
        pushDestGeneralHistoryLog("<D><b>${nationName}</b></>${josaWaSrc} 종전 성공")
        pushDestNationalHistoryLogFor(dn.id, "<D><b>${nationName}</b></>${josaWaSrc} 종전")

        return CommandResult(true, logs)
    }
}
