package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_몰수(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "몰수"

    override val fullConditionConstraints = listOf(
        NotBeNeutral(), OccupiedCity(), BeChief(),
        NotOpeningPart(env.year - env.startYear), SuppliedCity(),
        ExistsDestGeneral(), FriendlyDestGeneral()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destGen = destGeneral ?: return CommandResult(false, logs, "대상 장수 정보를 찾을 수 없습니다")
        val isGold = arg?.get("isGold") as? Boolean ?: true
        val amount = ((arg?.get("amount") as? Number)?.toInt() ?: 100).coerceIn(100, 100000)
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        if (isGold) {
            val actual = amount.coerceAtMost(destGen.gold)
            destGen.gold -= actual
            n.gold += actual
        } else {
            val actual = amount.coerceAtMost(destGen.rice)
            destGen.rice -= actual
            n.rice += actual
        }
        destGen.betray = (destGen.betray + 1).toShort().coerceAtMost(5)
        val resName = if (isGold) "금" else "쌀"
        pushLog("<Y>${destGen.name}</>에게서 $resName <C>$amount</>을(를) 몰수했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
