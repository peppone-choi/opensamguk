package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

private const val INITIAL_NATION_GEN_LIMIT = 10
private const val STRATEGIC_GLOBAL_DELAY = 9

class che_의병모집(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "의병모집"

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), OccupiedCity(),
        AvailableStrategicCommand(),
        NotOpeningPart(env.year - env.startYear)
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 2

    override fun getPostReqTurn(): Int {
        val genCount = max(INITIAL_NATION_GEN_LIMIT, INITIAL_NATION_GEN_LIMIT)
        return (sqrt(genCount * 10.0) * 10).roundToInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val c = city ?: return CommandResult(false, logs, "도시 정보를 찾을 수 없습니다")
        n.strategicCmdLimit = STRATEGIC_GLOBAL_DELAY.toShort()
        // 의병 3명 생성 (도시 인구에서 차감)
        val npcCount = 3
        val popPerNpc = 5000
        val totalPop = npcCount * popPerNpc
        if (c.pop < totalPop) {
            pushLog("인구가 부족합니다. (필요: $totalPop) <1>$date</>")
            return CommandResult(false, logs, "인구가 부족합니다")
        }
        c.pop -= totalPop
        for (i in 1..npcCount) {
            val leadership = (50 + rng.nextInt(30)).toShort()
            val strength = (50 + rng.nextInt(30)).toShort()
            val intel = (40 + rng.nextInt(20)).toShort()
            val npc = General(
                worldId = env.worldId,
                name = "${c.name}의병$i",
                nationId = n.id,
                cityId = c.id,
                npcState = 5,
                bornYear = (env.year - 20).toShort(),
                deadYear = (env.year + 10).toShort(),
                leadership = leadership,
                strength = strength,
                intel = intel,
                politics = 30,
                charm = 30,
                gold = 0,
                rice = 0,
                crew = 2000,
                crewType = 1,
                train = 70,
                atmos = 70,
                killTurn = 72,
            )
            services!!.generalRepository.save(npc)
        }
        pushLog("$actionName 발동! 의병 ${npcCount}명 모집. <1>$date</>")
        return CommandResult(true, logs)
    }
}
