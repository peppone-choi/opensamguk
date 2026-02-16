package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 선양(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "선양"

    override val fullConditionConstraints = listOf(
        BeLord(),
        ExistsDestGeneral(),
        FriendlyDestGeneral(),
    )

    override val minConditionConstraints = listOf(
        BeLord(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destGeneralName = destGeneral?.name ?: "알 수 없음"
        val nationName = nation?.name ?: "알 수 없음"

        pushLog("<Y>${destGeneralName}</>에게 군주의 자리를 물려줍니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"officerLevel":1,"officerCity":0},"destGeneralChanges":{"officerLevel":12,"officerCity":0}}"""
        )
    }
}
