package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val INITIAL_NATION_GEN_LIMIT = 8

class 임관(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "임관"

    override val fullConditionConstraints = listOf(
        BeNeutral(),
        ExistsDestNation(),
        AllowJoinAction(),
    )

    override val minConditionConstraints = listOf(
        BeNeutral(),
        AllowJoinAction(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dn = destNation!!
        val destNationName = dn.name

        pushLog("<D>${destNationName}</>에 임관했습니다. <1>$date</>")

        // TODO: check gennum for exp bonus
        val exp = 100

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"nation":${dn.id},"officerLevel":1,"officerCity":0,"belong":1,"troop":0,"experience":$exp},"nationChanges":{"gennum":1}}"""
        )
    }
}
