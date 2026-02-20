package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.roundToInt
import kotlin.random.Random

private const val MAX_TRAIN_BY_COMMAND = 80
private const val TRAIN_DELTA = 0.05
private const val ATMOS_SIDE_EFFECT_RATE = 0.9

class che_훈련(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "훈련"

    override val fullConditionConstraints: List<Constraint>
        get() = listOf(
            NotBeNeutral(),
            NotWanderingNation(),
            OccupiedCity(),
            ReqGeneralCrew(),
            ReqGeneralTrainMargin(MAX_TRAIN_BY_COMMAND)
        )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val leadership = general.leadership.toInt()
        val crew = general.crew
        val currentTrain = general.train.toInt()

        val rawScore = (leadership * 100.0 / crew) * TRAIN_DELTA
        val maxPossible = maxOf(0, MAX_TRAIN_BY_COMMAND - currentTrain)
        val score = minOf(maxOf(rawScore.roundToInt(), 0), maxPossible)

        val sideEffect = maxOf(0, (general.atmos * ATMOS_SIDE_EFFECT_RATE).toInt())

        pushLog("훈련치가 $score 상승했습니다. $date")

        val exp = 100
        val ded = 70

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"train":$score,"atmos":${sideEffect - general.atmos},"experience":$exp,"dedication":$ded,"leadershipExp":1},"dexChanges":{"crewType":${general.crewType},"amount":$score}}"""
        )
    }
}
