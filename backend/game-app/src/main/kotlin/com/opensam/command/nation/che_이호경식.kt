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

class che_이호경식(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "이호경식"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), ExistsDestNation(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0

    override fun getPostReqTurn(): Int {
        val genCount = max(INITIAL_NATION_GEN_LIMIT, INITIAL_NATION_GEN_LIMIT)
        return (sqrt(genCount * 16.0) * 10).roundToInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        val dn = destNation ?: return CommandResult(false, logs, "대상 국가 정보를 찾을 수 없습니다")
        n.strategicCmdLimit = STRATEGIC_GLOBAL_DELAY.toShort()
        // 대상 국가와 무작위 다른 국가 사이에 선전포고
        val allNations = services!!.nationRepository.findByWorldId(env.worldId)
            .filter { it.id != n.id && it.id != dn.id && it.level > 0 }
        if (allNations.isEmpty()) {
            pushLog("이간할 대상 국가가 없습니다. <1>$date</>")
            return CommandResult(false, logs, "이간할 대상 국가가 없습니다")
        }
        val target2 = allNations[rng.nextInt(allNations.size)]
        services!!.diplomacyService.declareWar(env.worldId, dn.id, target2.id)
        pushLog("$actionName 발동! <D><b>${dn.name}</b></> ↔ <D><b>${target2.name}</b></> 전쟁 유발. <1>$date</>")
        return CommandResult(true, logs)
    }
}
