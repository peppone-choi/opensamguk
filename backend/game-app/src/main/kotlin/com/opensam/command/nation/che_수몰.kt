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

class che_수몰(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "수몰"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(),
        NotNeutralDestCity(), NotOccupiedDestCity(),
        BattleGroundCity(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 2

    override fun getPostReqTurn(): Int {
        val genCount = min(max(1, 1), 30)
        return (sqrt(genCount * 4.0) * 10).roundToInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dc = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")
        n.strategicCmdLimit = 9
        // 적 도시 방어/성벽 80% 파괴
        dc.def = (dc.def * 0.2).toInt()
        dc.wall = (dc.wall * 0.2).toInt()
        // 인구 피해
        dc.pop = (dc.pop * 0.5).toInt()
        dc.dead += (dc.pop * 0.1).toInt()
        pushLog("<G><b>${dc.name}</b></> $actionName 발동! 방어/성벽 80% 파괴. <1>$date</>")
        return CommandResult(true, logs)
    }
}
