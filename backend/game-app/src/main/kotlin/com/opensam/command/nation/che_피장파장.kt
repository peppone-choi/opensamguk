package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_피장파장(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "피장파장"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), ExistsDestNation(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 1
    override fun getPostReqTurn() = 8

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dn = destNation ?: return CommandResult(false, logs, "대상 국가 정보를 찾을 수 없습니다")
        n.strategicCmdLimit = 9
        // 적 국가의 전략 쿨다운을 최대화
        dn.strategicCmdLimit = dn.strategicCmdLimit.coerceAtLeast(9).toShort()
        pushLog("<D><b>${dn.name}</b></> 전략의 $actionName 발동! 적 전략 쿨다운 부과. <1>$date</>")
        return CommandResult(true, logs)
    }
}
