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
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val nationName = nation?.name ?: "알 수 없음"

        pushLog("모반에 성공했습니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"officerLevel":12,"officerCity":0},"rebellionResult":{"success":true}}"""
        )
    }
}
