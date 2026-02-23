package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 모반시도(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "모반시도"

    override val fullConditionConstraints = listOf(
        NotBeNeutral(),
        BeChief(),
        OccupiedCity(),
        SuppliedCity(),
        NotLord(),
        AllowRebellion(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val generalName = general.name
        val nationName = nation?.name ?: "알 수 없음"
        val josaYi = pickJosa(generalName, "이")

        pushLog("모반에 성공했습니다. <1>$date</>")
        pushHistoryLog("모반으로 <D><b>${nationName}</b></>의 군주자리를 찬탈")
        pushGlobalLog("<Y><b>【모반】</b></><Y>${generalName}</>${josaYi} <D><b>${nationName}</b></>의 군주 자리를 찬탈했습니다.")
        pushNationalHistoryLog("<Y>${generalName}</>${josaYi} 군주자리를 찬탈")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"officerLevel":12,"officerCity":0},"rebellionResult":{"success":true},"lordChanges":{"officerLevel":1,"officerCity":0,"experienceMultiplier":0.7},"lordLogs":{"action":"<Y>${generalName}</>에게 군주의 자리를 뺏겼습니다.","history":"<D><b>${generalName}</b></>의 모반으로 인해 <D><b>${nationName}</b></>의 군주자리를 박탈당함"}}"""
        )
    }
}
