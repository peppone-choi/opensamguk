package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class event_원융노병연구(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "원융노병 연구"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(),
        ReqNationGold(1000 + 100000), ReqNationRice(1000 + 100000)
    )

    override fun getCost() = CommandCost(gold = 100000, rice = 100000)
    override fun getPreReqTurn() = 23
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, listOf("국가 정보를 찾을 수 없습니다"))
        val cost = getCost()
        n.gold -= cost.gold
        n.rice -= cost.rice
        n.meta["can_원융노병사용"] = 1
        general.experience += 100
        general.dedication += 100
        pushLog("<M>$actionName</> 완료")
        return CommandResult(true, logs)
    }
}
