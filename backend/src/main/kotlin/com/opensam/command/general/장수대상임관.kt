package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 장수대상임관(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "장수를 따라 임관"

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
        val destCityId = destGeneral?.cityId ?: dn.capitalCityId ?: 0L

        pushLog("<D>${destNationName}</>에 임관했습니다. <1>$date</>")

        // TODO: check gennum for exp bonus
        val exp = 100

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"nation":${dn.id},"officerLevel":1,"officerCity":0,"belong":1,"city":$destCityId,"experience":$exp},"nationChanges":{"gennum":1}}"""
        )
    }
}
