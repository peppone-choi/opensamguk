package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val PRE_REQ_TURN = 1
private const val POST_REQ_TURN = 8
private const val STRATEGIC_GLOBAL_DELAY = 9

class che_피장파장(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "피장파장"

    override val fullConditionConstraints = listOf(
        OccupiedCity(),
        BeChief(),
        ExistsDestNation(),
        AvailableStrategicCommand(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = PRE_REQ_TURN
    override fun getPostReqTurn() = POST_REQ_TURN

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dn = destNation ?: return CommandResult(false, logs, "대상 국가 정보를 찾을 수 없습니다")
        val commandType = arg?.get("commandType") as? String ?: "전략"

        val expDed = 5 * (PRE_REQ_TURN + 1)
        general.experience += expDed
        general.dedication += expDed

        n.strategicCmdLimit = STRATEGIC_GLOBAL_DELAY.toShort()
        dn.strategicCmdLimit = STRATEGIC_GLOBAL_DELAY.toShort()

        // Broadcast to friendly generals
        val nationGenerals = services?.generalRepository?.findByNationId(n.id) ?: emptyList()
        val destNationGenerals = services?.generalRepository?.findByNationId(dn.id) ?: emptyList()

        pushLog("<G><b>${commandType}</b></> 전략의 $actionName 발동! <1>$date</>")
        return CommandResult(true, logs)
    }
}
