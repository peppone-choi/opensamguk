package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.round
import kotlin.random.Random

private const val DEFAULT_MAX_TRAIN_BY_COMMAND = 80
private const val DEFAULT_MAX_ATMOS_BY_COMMAND = 80

class CR맹훈련(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "맹훈련"

    private val maxTrain: Int
        get() = if (env.maxTrainByCommand > 0) env.maxTrainByCommand else DEFAULT_MAX_TRAIN_BY_COMMAND

    private val maxAtmos: Int
        get() = if (env.maxAtmosByCommand > 0) env.maxAtmosByCommand else DEFAULT_MAX_ATMOS_BY_COMMAND

    override val fullConditionConstraints: List<Constraint>
        get() = listOf(
            NotBeNeutral(),
            NotWanderingNation(),
            OccupiedCity(),
            ReqGeneralCrew(),
            ReqGeneralTrainMargin(maxTrain),
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
        val trainDelta = if (env.trainDelta > 0) env.trainDelta else 0.05
        // Legacy: round(leadership * 100 / crew * trainDelta * 2 / 3)
        val rawScore = round((leadership * 100.0 / crew) * trainDelta * 2.0 / 3.0).toInt()
        val currentTrain = general.train.toInt()
        val currentAtmos = general.atmos.toInt()
        val trainGain = minOf(rawScore, maxOf(0, maxTrain - currentTrain))
        val atmosGain = minOf(rawScore, maxOf(0, maxAtmos - currentAtmos))
        val cost = getCost()

        pushLog("훈련, 사기치가 <C>${rawScore}</> 상승했습니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"rice":${-cost.rice},"train":$trainGain,"atmos":$atmosGain,"experience":150,"dedication":100,"leadershipExp":1},"dexChanges":{"crewType":${general.crewType},"amount":${rawScore * 2}}}"""
        )
    }
}
