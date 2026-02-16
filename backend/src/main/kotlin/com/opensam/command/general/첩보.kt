package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 첩보(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "첩보"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotOccupiedDestCity(),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
            )
        }

    override val minConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
                NotBeNeutral(),
            )
        }

    override fun getCost() = CommandCost(gold = 15, rice = 15)

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dc = destCity!!
        val destCityName = dc.name

        pushLog("누군가가 ${destCityName}을 살피는 것 같습니다.")
        pushLog("${destCityName}의 정보를 많이 얻었습니다. <1>$date</>")
        pushLog("【$destCityName】주민:${dc.pop}, 민심:${dc.trust}, 장수:0, 병력:0")
        pushLog("【첩보】농업:${dc.agri}, 상업:${dc.comm}, 치안:${dc.secu}, 수비:${dc.def}, 성벽:${dc.wall}")

        val exp = rng.nextInt(1, 101)
        val ded = rng.nextInt(1, 71)
        val cost = getCost()

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-cost.gold},"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"leadershipExp":1},"spyResult":{"destCityId":${dc.id},"distance":1}}"""
        )
    }
}
