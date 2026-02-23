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
private const val MAX_ATMOS_BY_COMMAND = 80
private const val PRE_REQ_TURN = 3

class 전투태세(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "전투태세"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                NotWanderingNation(),
                OccupiedCity(),
                ReqGeneralCrew(),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
                ReqGeneralTrainMargin(MAX_TRAIN_BY_COMMAND - 10),
                ReqGeneralAtmosMargin(MAX_ATMOS_BY_COMMAND - 10),
            )
        }

    override val minConditionConstraints: List<Constraint> = listOf(
        NotBeNeutral(),
        NotWanderingNation(),
        OccupiedCity(),
        ReqGeneralCrew(),
    )

    override fun getCost(): CommandCost {
        val crew = general.crew
        val techCost = getNationTechCost()
        val gold = (crew / 100.0 * 3 * techCost).roundToInt()
        return CommandCost(gold = gold, rice = 0)
    }

    override fun getPreReqTurn() = PRE_REQ_TURN
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val reqTurn = getPreReqTurn()

        // Multi-turn tracking: read previous term from lastTurn
        val previousTerm = getLastTurnTerm()
        val term = if (previousTerm >= reqTurn) 1 else previousTerm + 1

        if (term < reqTurn) {
            pushLog("병사들을 열심히 훈련중... ($term/$reqTurn) <1>$date</>")

            val cost = getCost()
            return CommandResult(
                success = true,
                logs = logs,
                message = """{"statChanges":{"gold":${-cost.gold}},"battleStanceTerm":$term,"completed":false}"""
            )
        }

        // Term == reqTurn: completion
        pushLog("전투태세 완료! ($term/$reqTurn) <1>$date</>")

        val exp = 100 * reqTurn
        val ded = 70 * reqTurn
        val dexGain = (general.crew / 100.0 * reqTurn).roundToInt()
        val trainTarget = MAX_TRAIN_BY_COMMAND - 5
        val atmosTarget = MAX_ATMOS_BY_COMMAND - 5
        val cost = getCost()

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-cost.gold},"train":{"setMin":$trainTarget},"atmos":{"setMin":$atmosTarget},"experience":$exp,"dedication":$ded,"leadershipExp":$reqTurn},"dexChanges":{"crewType":${general.crewType},"amount":$dexGain},"battleStanceTerm":$term,"completed":true}"""
        )
    }
}
