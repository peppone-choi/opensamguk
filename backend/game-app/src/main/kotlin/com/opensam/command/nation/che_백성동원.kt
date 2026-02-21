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

class che_백성동원(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "백성동원"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), OccupiedDestCity(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0

    override fun getPostReqTurn(): Int {
        val genCount = max(INITIAL_NATION_GEN_LIMIT, INITIAL_NATION_GEN_LIMIT)
        return (sqrt(genCount * 4.0) * 10).roundToInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dCity = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")
        val date = formatDate()
        n.strategicCmdLimit = 9
        val popNeeded = 10000
        if (dCity.pop < popNeeded) {
            pushLog("인구가 부족합니다. (필요: $popNeeded, 현재: ${dCity.pop}) <1>$date</>")
            return CommandResult(false, logs, "인구가 부족합니다")
        }
        val npcCount = (dCity.pop / popNeeded).coerceIn(1, 5)
        val crewPerNpc = 1000
        dCity.pop -= npcCount * popNeeded
        for (i in 1..npcCount) {
            val leadership = (40 + rng.nextInt(30)).toShort()
            val strength = (40 + rng.nextInt(30)).toShort()
            val intel = (40 + rng.nextInt(30)).toShort()
            val npc = General(
                worldId = env.worldId,
                name = "${dCity.name}동원병$i",
                nationId = n.id,
                cityId = dCity.id,
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
                crew = crewPerNpc,
                crewType = 1,
                train = 50,
                atmos = 50,
                killTurn = 72,
            )
            services!!.generalRepository.save(npc)
        }
        pushLog("<G><b>${dCity.name}</b></>에서 백성 <C>$npcCount</>명을 동원했습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
