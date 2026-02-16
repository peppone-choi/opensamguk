package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.entity.General
import kotlin.random.Random

class 휴식(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "휴식"

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        pushLog("휴식을 취했습니다. <1>$date</>")
        return CommandResult(success = true, logs = logs)
    }
}
