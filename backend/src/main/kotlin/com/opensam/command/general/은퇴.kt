package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val REQ_AGE = 60

class 은퇴(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "은퇴"

    override val fullConditionConstraints = listOf(
        ReqGeneralAge(REQ_AGE),
    )

    override val minConditionConstraints = listOf(
        ReqGeneralAge(REQ_AGE),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 1
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()

        pushLog("은퇴하였습니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"rebirth":true,"checkHall":true}"""
        )
    }
}
