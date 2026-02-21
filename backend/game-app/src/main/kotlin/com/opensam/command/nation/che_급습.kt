package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

private const val STRATEGIC_GLOBAL_DELAY = 9

class che_급습(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "급습"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), ExistsDestNation(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0

    override fun getPostReqTurn(): Int {
        val genCount = min(max(1, 1), 30)
        return (sqrt(genCount * 16.0) * 10).roundToInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dn = destNation ?: return CommandResult(false, logs, "대상 국가 정보를 찾을 수 없습니다")
        n.strategicCmdLimit = STRATEGIC_GLOBAL_DELAY.toShort()
        // 대상 국가와의 외교 기한 3개월 단축
        val relations = services!!.diplomacyService.getRelationsForNation(env.worldId, dn.id)
        var shortened = 0
        for (rel in relations) {
            if (rel.term > 0) {
                rel.term = max(0, rel.term - 3).toShort()
                shortened++
            }
        }
        pushLog("<D><b>${dn.name}</b></> 대상 $actionName 발동! 외교기한 ${shortened}건 단축. <1>$date</>")
        return CommandResult(true, logs)
    }
}
