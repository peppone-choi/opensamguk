package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.random.Random

class 거병(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "거병"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val relYear = env.year - env.startYear
            return listOf(
                BeNeutral(),
                BeOpeningPart(relYear + 1),
                AllowJoinAction(),
                NoPenalty("noFoundNation"),
            )
        }

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val generalName = general.name
        val cityName = city?.name ?: "알 수 없음"
        val josaYi = JosaUtil.pick(generalName, "이")

        // Handle nation name dedup (legacy parity)
        var nationName = generalName
        // Note: actual dedup check against existing nations happens server-side
        // The message includes the requested name; server will prepend ㉥ if needed

        pushLog("거병에 성공하였습니다. <1>$date</>")

        val exp = 100
        val ded = 100

        // Legacy: secretlimit = 3 (or 1 if scenario >= 1000)
        val secretLimit = if (env.scenario >= 1000) 1 else 3

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"experience":$exp,"dedication":$ded,"belong":1,"officerLevel":12,"officerCity":0},"nationChanges":{"createWanderingNation":true,"nationName":"$nationName","secretLimit":$secretLimit},"historyLog":{"global":"<Y><b>【거병】</b></><D><b>${generalName}</b></>${josaYi} 세력을 결성하였습니다.","globalAction":"<Y>${generalName}</>${josaYi} <G><b>${cityName}</b></>에 거병하였습니다.","general":"<G><b>${cityName}</b></>에서 거병","nation":"<Y>${generalName}</>${josaYi} <G><b>${cityName}</b></>에서 거병"},"inheritancePoint":{"active_action":1}}"""
        )
    }
}
