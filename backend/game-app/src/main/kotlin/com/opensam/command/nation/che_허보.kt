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

class che_허보(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "허보"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(),
        NotNeutralDestCity(), NotOccupiedDestCity(),
        AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 1

    override fun getPostReqTurn(): Int {
        val genCount = min(max(1, 1), 30)
        return (sqrt(genCount * 4.0) * 10).roundToInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dc = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")
        n.strategicCmdLimit = 9
        // 적 도시의 장수들을 무작위 도시로 이동
        val enemyGenerals = services!!.generalRepository.findByCityId(dc.id)
            .filter { it.nationId == dc.nationId }
        val enemyCities = services!!.cityRepository.findByNationId(dc.nationId)
            .filter { it.id != dc.id }
        if (enemyCities.isEmpty()) {
            pushLog("<G><b>${dc.name}</b></> 이동시킬 도시가 없습니다. <1>$date</>")
            return CommandResult(false, logs, "이동시킬 도시가 없습니다")
        }
        var moved = 0
        for (gen in enemyGenerals) {
            val targetCity = enemyCities[rng.nextInt(enemyCities.size)]
            gen.cityId = targetCity.id
            if (gen.troopId != 0L && gen.troopId != gen.id) {
                gen.troopId = 0
            }
            services!!.generalRepository.save(gen)
            moved++
        }
        pushLog("<G><b>${dc.name}</b></> $actionName 발동! 장수 ${moved}명 분산. <1>$date</>")
        return CommandResult(true, logs)
    }
}
