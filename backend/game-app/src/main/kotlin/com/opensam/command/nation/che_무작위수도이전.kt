package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class che_무작위수도이전(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "무작위 수도 이전"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeLord(), SuppliedCity(),
        BeOpeningPart(env.year - env.startYear + 1)
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 1
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val nationName = nation?.name ?: "알 수 없음"
        @Suppress("UNCHECKED_CAST")
        val neutralCities = env.gameStor["neutralCities"] as? List<Int> ?: emptyList()
        if (neutralCities.isEmpty()) {
            pushLog("이동할 수 있는 도시가 없습니다. <1>$date</>")
            return CommandResult(false, logs, "이동할 수 있는 도시가 없습니다")
        }
        val destCityId = neutralCities[rng.nextInt(neutralCities.size)]
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val targetCity = services!!.cityRepository.findById(destCityId.toLong()).orElse(null)
            ?: return CommandResult(false, logs, "도시를 찾을 수 없습니다")
        targetCity.nationId = n.id
        services!!.cityRepository.save(targetCity)
        n.capitalCityId = targetCity.id
        pushLog("<G><b>${targetCity.name}</b></>(으)로 국가를 옮겼습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
