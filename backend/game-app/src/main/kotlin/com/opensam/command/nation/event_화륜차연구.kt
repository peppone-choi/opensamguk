package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.random.Random

class event_화륜차연구(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "화륜차 연구"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(),
        ReqNationAuxValue("can_화륜차사용", 0, "<", 1, "${actionName}가 이미 완료되었습니다."),
        ReqNationGold(1000 + 100000), ReqNationRice(1000 + 100000)
    )

    override val minConditionConstraints get() = fullConditionConstraints

    override fun getCost() = CommandCost(gold = 100000, rice = 100000)
    override fun getPreReqTurn() = 23
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, listOf("국가 정보를 찾을 수 없습니다"))
        val cost = getCost()
        n.gold -= cost.gold
        n.rice -= cost.rice
        n.meta["can_화륜차사용"] = 1

        val expDed = 100
        general.experience += expDed
        general.dedication += expDed

        val generalName = general.name
        val josaYi = JosaUtil.pick(generalName, "이")

        pushLog("<M>$actionName</> 완료")
        pushLog("_history:<M>$actionName</> 완료")
        pushLog("_nation_history:<Y>${generalName}</>${josaYi} <M>$actionName</> 완료")
        return CommandResult(true, logs)
    }
}
