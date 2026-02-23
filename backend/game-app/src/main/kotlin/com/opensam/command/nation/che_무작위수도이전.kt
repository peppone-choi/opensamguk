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
        BeOpeningPart(env.year - env.startYear + 1),
        ReqNationAuxValue("can_무작위수도이전", 0, ">", 0, "더이상 변경이 불가능합니다.")
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 1
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val oldCityId = n.capitalCityId

        // Find neutral cities with level 5-6
        val allCities = services!!.cityRepository.findByWorldId(env.worldId)
        val neutralCities = allCities.filter { it.nationId == 0L && it.level in 5..6 }

        if (neutralCities.isEmpty()) {
            pushLog("이동할 수 있는 도시가 없습니다. <1>$date</>")
            return CommandResult(false, logs, "이동할 수 있는 도시가 없습니다")
        }

        val destCity = neutralCities[rng.nextInt(neutralCities.size)]

        // Set new capital city to nation ownership
        destCity.nationId = n.id
        n.capitalCityId = destCity.id

        // Decrement aux counter
        val canCount = (n.meta["can_무작위수도이전"] as? Number)?.toInt() ?: 0
        n.meta["can_무작위수도이전"] = canCount - 1

        // Release old capital to neutral
        if (oldCityId != null) {
            val oldCity = services!!.cityRepository.findById(oldCityId).orElse(null)
            if (oldCity != null) {
                oldCity.nationId = 0
                oldCity.frontState = 0
                oldCity.officerSet = 0
            }
        }

        // Move all nation generals to new capital
        val nationGenerals = services!!.generalRepository.findByWorldIdAndNationId(env.worldId, n.id)
        for (g in nationGenerals) {
            g.cityId = destCity.id
        }

        general.experience += 5 * (getPreReqTurn() + 1)
        general.dedication += 5 * (getPreReqTurn() + 1)

        pushLog("<G><b>${destCity.name}</b></>으로 국가를 옮겼습니다. <1>$date</>")
        return CommandResult(true, logs)
    }
}
