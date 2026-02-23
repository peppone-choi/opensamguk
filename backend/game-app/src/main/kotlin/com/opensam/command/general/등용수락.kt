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
private const val MAX_BETRAY_CNT = 10

class 등용수락(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "등용 수락"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val relYear = env.year - env.startYear
            return listOf(
                ReqEnvValue("join_mode", "!=", "onlyRandom", "랜덤 임관만 가능합니다"),
                ExistsDestNation(),
                BeNeutral(),
                AllowJoinDestNation(relYear),
                ReqDestNationValue("level", "국가규모", ">", 0, "방랑군에는 임관할 수 없습니다."),
                DifferentDestNation(),
                ReqGeneralValue({ it.officerLevel.toInt() }, "직위", 12, negate = true,
                    failMessage = "군주는 등용장을 수락할 수 없습니다"),
            )
        }

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val destNationName = destNation?.name ?: "알 수 없음"
        val destNationId = destNation?.id ?: 0L
        val capitalCityId = destNation?.capitalCityId ?: 0L
        val generalName = general.name
        val isTroopLeader = general.troopId == general.id

        // Self log
        pushLog("<D>${destNationName}</>로 망명하여 수도로 이동합니다.")
        // Global log
        val josaYi = pickJosa(generalName, "이")
        val josaRo = pickJosa(destNationName, "로")
        pushGlobalLog("<Y>${generalName}</>${josaYi} <D><b>${destNationName}</b></>${josaRo} <S>망명</>하였습니다.")
        // History logs
        pushHistoryLog("<D><b>${destNationName}</b></>${josaRo} 망명")

        val statChanges = mutableMapOf<String, Any>(
            "permission" to "normal",
            "belong" to 1,
            "officerLevel" to 1,
            "officerCity" to 0,
            "nation" to destNationId,
            "city" to capitalCityId,
            "troop" to 0,
        )

        // Recruiter rewards
        val recruiterChanges = mutableMapOf<String, Any>(
            "experience" to 100,
            "dedication" to 100,
        )

        if (general.nationId != 0L) {
            // Return excess gold/rice to original nation
            if (general.gold > DEFAULT_GOLD) {
                statChanges["gold"] = DEFAULT_GOLD
                statChanges["returnGold"] = general.gold - DEFAULT_GOLD
            }
            if (general.rice > DEFAULT_RICE) {
                statChanges["rice"] = DEFAULT_RICE
                statChanges["returnRice"] = general.rice - DEFAULT_RICE
            }
            // Betrayal penalty: 10% * betray count
            val betrayPenalty = 0.1 * general.betray
            statChanges["experience"] = floor(general.experience * (1 - betrayPenalty)).toInt()
            statChanges["dedication"] = floor(general.dedication * (1 - betrayPenalty)).toInt()
            statChanges["betray"] = min(general.betray + 1, MAX_BETRAY_CNT)
        } else {
            // Neutral -> Join: Bonus exp/ded
            statChanges["experience"] = general.experience.toInt() + 100
            statChanges["dedication"] = general.dedication.toInt() + 100
        }

        // Reset killturn for non-NPC
        if (general.npcType < 2) {
            statChanges["killturn"] = env.killturn
        }

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":${toJson(statChanges)},"recruiterChanges":${toJson(recruiterChanges)},"troopDisband":$isTroopLeader,"destGeneralLog":"<Y>${generalName}</> 등용에 성공했습니다."}"""
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
