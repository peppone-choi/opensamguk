package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.roundToInt
import kotlin.random.Random

// Default values; prefer env.maxTrainByCommand and env.trainDelta when available
private const val DEFAULT_MAX_TRAIN_BY_COMMAND = 80
private const val DEFAULT_TRAIN_DELTA = 0.05
private const val ATMOS_SIDE_EFFECT_RATE = 0.9

class che_훈련(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "훈련"

    private val maxTrain: Int
        get() = if (env.maxTrainByCommand > 0) env.maxTrainByCommand else DEFAULT_MAX_TRAIN_BY_COMMAND

    private val trainDelta: Double
        get() = if (env.trainDelta > 0) env.trainDelta else DEFAULT_TRAIN_DELTA

    override val fullConditionConstraints: List<Constraint>
        get() = listOf(
            NotBeNeutral(),
            NotWanderingNation(),
            OccupiedCity(),
            ReqGeneralCrew(),
            ReqGeneralTrainMargin(maxTrain)
        )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val leadership = general.leadership.toInt()
        val crew = if (general.crew > 0) general.crew else 1
        val currentTrain = general.train.toInt()

        // Legacy: clamp(round(leadership * 100 / crew * trainDelta), 0, maxTrain - currentTrain)
        val rawScore = (leadership * 100.0 / crew) * trainDelta
        val maxPossible = maxOf(0, maxTrain - currentTrain)
        val score = minOf(maxOf(rawScore.roundToInt(), 0), maxPossible)

        // Legacy: atmos side effect = atmos * atmosSideEffectByTraining (default 0.9)
        val atmosAfter = maxOf(0, (general.atmos * ATMOS_SIDE_EFFECT_RATE).toInt())
        val atmosDelta = atmosAfter - general.atmos.toInt()

        pushLog("훈련치가 <C>${score}</> 상승했습니다. <1>$date</>")

        val exp = 100
        val ded = 70

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"train":$score,"atmos":$atmosDelta,"experience":$exp,"dedication":$ded,"leadershipExp":1},"dexChanges":{"crewType":${general.crewType},"amount":$score}}"""
        )
    }
}
