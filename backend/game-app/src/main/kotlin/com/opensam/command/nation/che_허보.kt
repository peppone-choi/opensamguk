package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val PRE_REQ_TURN = 1
private const val DEFAULT_GLOBAL_DELAY: Short = 9

class che_허보(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "허보"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(),
        NotNeutralDestCity(), NotOccupiedDestCity(),
        AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = PRE_REQ_TURN
    override fun getPostReqTurn() = 20

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dc = destCity ?: return CommandResult(false, logs, "대상 도시 정보를 찾을 수 없습니다")

        val expDed = 5 * (PRE_REQ_TURN + 1)
        general.experience += expDed
        general.dedication += expDed

        // Set strategic command limit
        n.strategicCmdLimit = DEFAULT_GLOBAL_DELAY.toShort()

        // Move enemy generals at destCity to random supply cities of their nation
        val enemyGenerals = services?.generalRepository?.findByCityId(dc.id)
            ?.filter { it.nationId == dc.nationId } ?: emptyList()
        val enemySupplyCities = services?.cityRepository?.findByNationId(dc.nationId)
            ?.filter { it.supplyState > 0 && it.id != dc.id } ?: emptyList()

        var moved = 0
        for (gen in enemyGenerals) {
            if (enemySupplyCities.isEmpty()) continue
            val targetCity = enemySupplyCities[rng.nextInt(enemySupplyCities.size)]
            gen.cityId = targetCity.id
            if (gen.troopId != gen.id) {
                gen.troopId = 0
            }
            services?.generalRepository?.save(gen)
            moved++
        }

        pushLog("$actionName 발동! <1>$date</>")
        return CommandResult(true, logs)
    }
}
