package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.round
import kotlin.random.Random

private const val MAX_TRAIN_BY_COMMAND = 80

class CR맹훈련(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "맹훈련"

    override val fullConditionConstraints = listOf(
        NotBeNeutral(),
        NotWanderingNation(),
        OccupiedCity(),
        ReqGeneralCrew(),
        ReqGeneralTrainMargin(MAX_TRAIN_BY_COMMAND),
    )

    override val minConditionConstraints = listOf(
        NotBeNeutral(),
        NotWanderingNation(),
        OccupiedCity(),
    )

    override fun getCost() = CommandCost(rice = 500)
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val crew = if (general.crew > 0) general.crew else 1
        val leadership = general.leadership.toInt()
        val score = round(((leadership * 100.0) / crew) * 2 / 3).toInt()
        val cost = getCost()

        pushLog("훈련, 사기치가 <C>${score}</> 상승했습니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"rice":${-cost.rice},"train":$score,"atmos":$score,"experience":150,"dedication":100,"leadershipExp":1},"dexChanges":{"crewType":${general.crewType},"amount":${score * 2}}}"""
        )
    }
}
