package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.random.Random

class che_선전포고(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "선전포고"

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), OccupiedCity(), SuppliedCity(),
        ReqEnvValue("year", ">=", env.startYear + 1, "초반제한 해제 2년전부터 가능합니다."),
        ExistsDestNation(), NearNation(),
        DisallowDiplomacyBetweenStatus(mapOf(
            0 to "아국과 이미 교전중입니다.",
            1 to "아국과 이미 선포중입니다.",
            7 to "불가침국입니다."
        ))
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, listOf("국가 정보를 찾을 수 없습니다"))
        val dn = destNation ?: return CommandResult(false, listOf("대상 국가 정보를 찾을 수 없습니다"))
        val date = formatDate()
        val generalName = general.name
        val nationName = n.name
        val destNationName = dn.name

        val josaYi = JosaUtil.pick(generalName, "이")
        val josaYiNation = JosaUtil.pick(nationName, "이")

        // Update diplomacy: state=1 (declaration), term=24
        services!!.diplomacyService.setDiplomacyState(env.worldId, n.id, dn.id, state = 1, term = 24)

        // General action log
        pushLog("<D><b>${destNationName}</b></>에 선전 포고 했습니다.<1>$date</>")
        // General history log
        pushHistoryLog("<D><b>${destNationName}</b></>에 선전 포고")
        // Own national history
        pushNationalHistoryLog("<Y>${generalName}</>${josaYi} <D><b>${destNationName}</b></>에 선전 포고")
        // Dest national history
        pushDestNationalHistoryLog("<D><b>${nationName}</b></>의 <Y>${generalName}</>${josaYi} 아국에 선전 포고")

        // Global action log
        pushGlobalActionLog("<Y>${generalName}</>${josaYi} <D><b>${destNationName}</b></>에 <M>선전 포고</> 하였습니다.")
        // Global history log
        pushGlobalHistoryLog("<R><b>【선포】</b></><D><b>${nationName}</b></>${josaYiNation} <D><b>${destNationName}</b></>에 선전 포고 하였습니다.")

        // National message to dest nation
        val text = "【외교】${env.year}년 ${env.month}월:${nationName}에서 ${destNationName}에 선전포고"
        services!!.messageService?.sendNationalMessage(
            worldId = env.worldId,
            srcNationId = n.id,
            destNationId = dn.id,
            srcGeneralId = general.id,
            text = text
        )

        return CommandResult(true, logs)
    }
}
