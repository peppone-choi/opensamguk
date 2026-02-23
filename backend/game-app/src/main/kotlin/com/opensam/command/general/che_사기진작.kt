package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.roundToInt
import kotlin.random.Random

private const val MAX_ATMOS_BY_COMMAND = 80
private const val ATMOS_DELTA = 0.05
private const val TRAIN_SIDE_EFFECT_RATE = 0.9

class che_사기진작(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "사기진작"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                NotWanderingNation(),
                OccupiedCity(),
                ReqGeneralCrew(),
                ReqGeneralGold(cost.gold),
                ReqGeneralAtmosMargin(MAX_ATMOS_BY_COMMAND)
            )
        }

    override fun getCost(): CommandCost {
        val gold = general.crew / 100
        return CommandCost(gold = gold)
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val leadership = general.leadership.toInt()
        val crew = general.crew
        val currentAtmos = general.atmos.toInt()

        val rawScore = (leadership * 100.0 / crew) * ATMOS_DELTA
        val maxPossible = maxOf(0, MAX_ATMOS_BY_COMMAND - currentAtmos)
        val score = minOf(maxOf(rawScore.roundToInt(), 0), maxPossible)

        val sideEffect = maxOf(0, (general.train * TRAIN_SIDE_EFFECT_RATE).toInt())

        val scoreText = "%,d".format(score)
        pushLog("사기치가 <C>${scoreText}</> 상승했습니다. <1>$date</>")

        val exp = 100
        val ded = 70
        val cost = getCost()

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-cost.gold},"atmos":$score,"train":${sideEffect - general.train},"experience":$exp,"dedication":$ded,"leadershipExp":1},"dexChanges":{"crewType":${general.crewType},"amount":$score}}"""
        )
    }
}
