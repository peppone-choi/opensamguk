package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

class 인재탐색(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "인재탐색"

    override val fullConditionConstraints: List<Constraint> by lazy {
        listOf(
            ReqGeneralGold(getCost().gold),
            ReqGeneralRice(getCost().rice),
        )
    }

    override fun getCost() = CommandCost(gold = env.develCost, rice = 0)
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    private fun calcFoundProp(maxGeneral: Double, totalGeneralCount: Double, totalNpcCount: Double): Double {
        val current = totalGeneralCount + totalNpcCount / 2.0
        val remainSlot = (maxGeneral - current).coerceAtLeast(0.0)
        val main = (remainSlot / maxGeneral).pow(6)
        val small = 1.0 / (totalNpcCount / 3.0 + 1.0)
        val big = 1.0 / maxGeneral
        return if (totalNpcCount < 50.0) maxOf(main, small) else maxOf(main, big)
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val reqGold = getCost().gold

        // Weighted random stat increase (legacy PHP parity)
        val leadershipW = general.leadership.toInt().coerceAtLeast(1)
        val strengthW = general.strength.toInt().coerceAtLeast(1)
        val intelW = general.intel.toInt().coerceAtLeast(1)
        val totalWeight = leadershipW + strengthW + intelW
        val rand = rng.nextInt(totalWeight)
        val incStat = when {
            rand < leadershipW -> "leadershipExp"
            rand < leadershipW + strengthW -> "strengthExp"
            else -> "intelExp"
        }

        val maxGeneral = (env.gameStor["maxGeneral"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: 500.0
        val totalGeneralCount = (env.gameStor["totalGeneralCount"] as? Number)?.toDouble() ?: 0.0
        val totalNpcCount = (env.gameStor["totalNpcCount"] as? Number)?.toDouble() ?: 0.0
        val foundProp = calcFoundProp(maxGeneral, totalGeneralCount, totalNpcCount)
        val foundNpc = rng.nextDouble() < foundProp

        if (!foundNpc) {
            pushLog("인재를 찾을 수 없었습니다. <1>$date</>")
            return CommandResult(
                success = true,
                logs = logs,
                message = """{"statChanges":{"gold":${-reqGold},"experience":100,"dedication":70,"$incStat":1},"tryUniqueLottery":true}"""
            )
        }

        // Legacy PHP: on success, incStat gets +3, exp=200, ded=300
        // Also: inheritance point += valueFit(sqrt(1/foundProp), 1)
        val inheritanceBonus = sqrt(1.0 / foundProp).coerceAtLeast(1.0)

        pushLog("인재를 발견하였습니다! <1>$date</>")
        // Legacy PHP: global action log with general name
        pushLog("[GLOBAL]<Y>${general.name}</>이(가) 인재를 발견하였습니다!")
        // Legacy PHP: general history log
        pushLog("[HISTORY]인재를 발견")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-reqGold},"experience":200,"dedication":300,"$incStat":3,"inheritanceBonus":$inheritanceBonus},"createNPC":{"type":"wandering"},"tryUniqueLottery":true}"""
        )
    }
}
