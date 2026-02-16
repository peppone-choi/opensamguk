package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val DECREASE_COEFF = 0.4
private const val CONVERT_COEFF = 0.9

private val ARM_TYPE_NAMES = mapOf(
    0 to "보병", 1 to "궁병", 2 to "기병",
    3 to "극병", 4 to "노병", 5 to "차병"
)

class che_숙련전환(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "숙련전환"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                OccupiedCity(),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice)
            )
        }

    override fun getCost() = CommandCost(gold = env.develCost, rice = env.develCost)
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        if (arg == null) {
            return CommandResult(success = false, logs = listOf("인자가 없습니다."))
        }

        val date = formatDate()
        val srcArmType = (arg!!["srcArmType"] as? Number)?.toInt() ?: 0
        val destArmType = (arg!!["destArmType"] as? Number)?.toInt() ?: 0
        val srcTypeName = ARM_TYPE_NAMES[srcArmType] ?: "?"
        val destTypeName = ARM_TYPE_NAMES[destArmType] ?: "?"

        // dex values stored in general.meta as "dex0", "dex1", etc.
        val srcDex = (general.meta["dex$srcArmType"] as? Number)?.toInt() ?: 0
        val cutDex = (srcDex * DECREASE_COEFF).toInt()
        val addDex = (cutDex * CONVERT_COEFF).toInt()

        val cost = getCost()

        pushLog("${srcTypeName} 숙련 ${cutDex}을(를) ${destTypeName} 숙련 ${addDex}로 전환했습니다. $date")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-cost.gold},"rice":${-cost.rice},"experience":10,"leadershipExp":2,"dex$srcArmType":${-cutDex},"dex$destArmType":$addDex},"dexConversion":{"srcType":$srcArmType,"destType":$destArmType,"cutDex":$cutDex,"addDex":$addDex}}"""
        )
    }
}
