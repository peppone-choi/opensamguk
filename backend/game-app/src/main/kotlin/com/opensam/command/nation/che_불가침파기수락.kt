package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_불가침파기수락(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "불가침 파기 수락"

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), ExistsDestNation(), ExistsDestGeneral()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val destNationName = destNation?.name ?: "알 수 없음"
        pushLog("<D><b>$destNationName</b></>와의 불가침을 파기했습니다.")
        return CommandResult(true, logs)
    }
}
