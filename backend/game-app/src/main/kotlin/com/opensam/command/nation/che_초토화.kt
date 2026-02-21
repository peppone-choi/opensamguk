package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val POST_REQ_TURN = 24

class che_초토화(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "초토화"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), OccupiedDestCity(), BeChief(),
        SuppliedCity(), SuppliedDestCity()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 2
    override fun getPostReqTurn() = POST_REQ_TURN

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dc = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")
        // 자국 도시를 초토화하여 자원 회수
        val returnGold = dc.pop / 10
        val returnRice = dc.pop / 10
        dc.pop = (dc.pop * 0.2).toInt()
        dc.agri = (dc.agri * 0.2).toInt()
        dc.comm = (dc.comm * 0.2).toInt()
        dc.secu = (dc.secu * 0.2).toInt()
        dc.def = (dc.def * 0.2).toInt()
        dc.wall = (dc.wall * 0.2).toInt()
        n.gold += returnGold
        n.rice += returnRice
        pushLog("<G><b>${dc.name}</b></>을 $actionName! 금${returnGold}/쌀${returnRice} 회수. <1>$date</>")
        return CommandResult(true, logs)
    }
}
