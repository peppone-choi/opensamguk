package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.pow
import kotlin.random.Random

class 인재탐색(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "인재탐색"

    override val fullConditionConstraints: List<Constraint> by lazy {
        listOf(
            ReqGeneralGold(getCost().gold),
        )
    }

    override fun getCost() = CommandCost(gold = env.develCost)
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val reqGold = getCost().gold

        // Weighted random stat increase
        val totalWeight = general.leadership + general.strength + general.intel
        var rand = rng.nextInt(totalWeight.toInt())
        val incStat = when {
            rand < general.leadership -> "leadershipExp"
            rand < general.leadership + general.strength -> "strengthExp"
            else -> "intelExp"
        }

        val maxGeneral = (env.gameStor["maxGeneral"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: 500.0
        val totalGeneralCount = (env.gameStor["totalGeneralCount"] as? Number)?.toDouble() ?: 0.0
        val totalNpcCount = (env.gameStor["totalNpcCount"] as? Number)?.toDouble() ?: 0.0
        val current = totalGeneralCount + totalNpcCount / 2.0
        val remainSlot = (maxGeneral - current).coerceAtLeast(0.0)
        val main = (remainSlot / maxGeneral).pow(6)
        val small = 1.0 / (totalNpcCount / 3.0 + 1.0)
        val big = 1.0 / maxGeneral
        val foundProp = if (totalNpcCount < 50.0) maxOf(main, small) else maxOf(main, big)
        val foundNpc = rng.nextDouble() < foundProp

        if (!foundNpc) {
            pushLog("인재를 찾을 수 없었습니다. <1>$date</>")
            return CommandResult(
                success = true,
                logs = logs,
                message = """{"statChanges":{"gold":${-reqGold},"experience":100,"dedication":70,"$incStat":1}}"""
            )
        }

        pushLog("인재를 발견하였습니다! <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-reqGold},"experience":200,"dedication":300,"$incStat":3},"createNPC":{"type":"wandering"}}"""
        )
    }
}
