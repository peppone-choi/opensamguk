package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private const val DEFAULT_GOLD = 1000
private const val DEFAULT_RICE = 1000
private const val MAX_BETRAY_COUNT = 10

class 하야(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "하야"

    override val fullConditionConstraints = listOf(
        NotBeNeutral(),
        NotLord(),
    )

    override val minConditionConstraints = listOf(
        NotBeNeutral(),
        NotLord(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val nationName = nation?.name ?: "소속국"

        pushLog("<D><b>${nationName}</b></>에서 하야했습니다. <1>$date</>")

        val betrayCount = general.betray.toInt()
        val penaltyRate = 0.1 * betrayCount
        val expLoss = floor(general.experience * penaltyRate).toInt()
        val dedLoss = floor(general.dedication * penaltyRate).toInt()
        val goldToNation = max(0, general.gold - DEFAULT_GOLD)
        val riceToNation = max(0, general.rice - DEFAULT_RICE)
        val newBetray = min(betrayCount + 1, MAX_BETRAY_COUNT)
        val isTroopLeader = general.troopId == general.id

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"experience":${-expLoss},"dedication":${-dedLoss},"gold":${-goldToNation},"rice":${-riceToNation},"betray":${newBetray - betrayCount}},"nationChanges":{"gold":$goldToNation,"rice":$riceToNation},"leaveNation":true,"resetOfficer":true,"disbandTroop":$isTroopLeader}"""
        )
    }
}
