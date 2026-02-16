package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.entity.General
import kotlin.random.Random

class 요양(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "요양"

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()

        pushLog("건강 회복을 위해 요양합니다. <1>$date</>")

        val exp = 10
        val ded = 7
        val injuryHeal = -general.injury.toInt()

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"injury":$injuryHeal,"experience":$exp,"dedication":$ded}}"""
        )
    }
}
