package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_소집해제(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "소집해제"

    override val fullConditionConstraints: List<Constraint>
        get() = listOf(ReqGeneralCrew())

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val crewToReturn = general.crew

        pushLog("병사들을 소집해제하였습니다. $date")

        val exp = 70
        val ded = 100

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"crew":${-crewToReturn},"experience":$exp,"dedication":$ded},"cityChanges":{"pop":$crewToReturn}}"""
        )
    }
}
