package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

private const val DEFAULT_GOLD = 1000
private const val DEFAULT_RICE = 1000

class 등용수락(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "등용 수락"

    override val fullConditionConstraints = listOf(
        BeNeutral(),
        ExistsDestNation(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val destNationName = destNation?.name ?: "알 수 없음"
        val destNationId = destNation?.id ?: 0L
        val capitalCityId = destNation?.capitalCityId ?: 0L
        val isTroopLeader = general.troopId == general.id

        pushLog("<D>${destNationName}</>로 망명하여 수도로 이동합니다.")

        val statChanges = mutableMapOf<String, Any>(
            "permission" to "normal",
            "belong" to 1,
            "officerLevel" to 1,
            "officerCity" to 0,
            "nation" to destNationId,
            "city" to capitalCityId,
            "troop" to 0,
        )

        if (general.nationId != 0L) {
            if (general.gold > DEFAULT_GOLD) {
                statChanges["gold"] = DEFAULT_GOLD
                statChanges["returnGold"] = general.gold - DEFAULT_GOLD
            }
            if (general.rice > DEFAULT_RICE) {
                statChanges["rice"] = DEFAULT_RICE
                statChanges["returnRice"] = general.rice - DEFAULT_RICE
            }
            val betrayPenalty = 0.1 * general.betray
            statChanges["experience"] = floor(general.experience * (1 - betrayPenalty)).toInt()
            statChanges["dedication"] = floor(general.dedication * (1 - betrayPenalty)).toInt()
            statChanges["betray"] = min(general.betray + 1, 10)
        } else {
            statChanges["experience"] = 100
            statChanges["dedication"] = 100
        }

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":${toJson(statChanges)},"troopDisband":$isTroopLeader}"""
        )
    }

    private fun toJson(map: Map<String, Any>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            when (v) {
                is String -> "\"$k\":\"$v\""
                else -> "\"$k\":$v"
            }
        }
        return "{$entries}"
    }
}
