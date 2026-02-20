package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private const val GENERAL_MINIMUM_GOLD = 100
private const val GENERAL_MINIMUM_RICE = 100
private const val MAX_RESOURCE_ACTION_AMOUNT = 10000

class 증여(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "증여"

    override val fullConditionConstraints = listOf(
        NotBeNeutral(),
        OccupiedCity(),
        SuppliedCity(),
        ExistsDestGeneral(),
        FriendlyDestGeneral(),
    )

    override val minConditionConstraints = listOf(
        NotBeNeutral(),
        OccupiedCity(),
        SuppliedCity(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dg = destGeneral ?: return CommandResult(
            success = false,
            logs = listOf("대상 장수를 찾을 수 없습니다."),
        )

        val isGold = arg?.get("isGold") as? Boolean ?: true
        val requestedAmount = (arg?.get("amount") as? Number)?.toInt() ?: 100
        val resName = if (isGold) "금" else "쌀"
        val minReserve = if (isGold) GENERAL_MINIMUM_GOLD else GENERAL_MINIMUM_RICE
        val currentRes = if (isGold) general.gold else general.rice
        val maxGiveable = max(0, currentRes - minReserve)
        val roundedAmount = (requestedAmount / 100) * 100
        val clampedAmount = max(100, min(roundedAmount, MAX_RESOURCE_ACTION_AMOUNT))
        val amount = min(clampedAmount, maxGiveable)

        if (amount <= 0) {
            return CommandResult(
                success = false,
                logs = listOf("증여할 ${resName}이 부족합니다."),
            )
        }

        val resKey = if (isGold) "gold" else "rice"
        pushLog("<Y>${dg.name}</>에게 ${resName} <C>${amount}</>을 증여했습니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"$resKey":${-amount},"experience":70,"dedication":100,"leadershipExp":1},"destGeneralChanges":{"generalId":"${dg.id}","$resKey":$amount}}"""
        )
    }
}
