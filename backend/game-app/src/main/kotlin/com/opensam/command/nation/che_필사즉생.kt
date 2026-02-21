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

class che_필사즉생(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "필사즉생"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), AvailableStrategicCommand()
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 2

    override fun getPostReqTurn(): Int {
        val genCount = min(max(1, 1), 30)
        return (sqrt(genCount * 8.0) * 10).roundToInt()
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")
        n.strategicCmdLimit = 9
        // 국가 전체 장수의 훈련/사기 100으로
        val nationGenerals = services!!.generalRepository.findByNationId(n.id)
        var affected = 0
        for (gen in nationGenerals) {
            gen.train = 100
            gen.atmos = 100
            services!!.generalRepository.save(gen)
            affected++
        }
        pushLog("$actionName 발동! 장수 ${affected}명 훈련/사기 최대. <1>$date</>")
        return CommandResult(true, logs)
    }
}
