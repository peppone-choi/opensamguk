package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.random.Random

class che_불가침제의(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "불가침 제의"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val year = arg?.get("year") as? Int
            val month = arg?.get("month") as? Int
            if (year != null && month != null) {
                val currentMonth = env.year * 12 + env.month - 1
                val reqMonth = year * 12 + month - 1
                if (reqMonth < currentMonth + 6) {
                    return listOf(AlwaysFail("기한은 6개월 이상이어야 합니다."))
                }
            }
            return listOf(
                BeChief(), NotBeNeutral(), ExistsDestNation(), DifferentDestNation(),
                DisallowDiplomacyBetweenStatus(mapOf(
                    0 to "아국과 이미 교전중입니다.",
                    1 to "아국과 이미 선포중입니다."
                ))
            )
        }

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, listOf("국가 정보를 찾을 수 없습니다"))
        val dn = destNation ?: return CommandResult(false, listOf("대상 국가 정보를 찾을 수 없습니다"))

        val year = arg?.get("year") as? Int ?: return CommandResult(false, listOf("연도 정보가 없습니다"))
        val month = arg?.get("month") as? Int ?: return CommandResult(false, listOf("월 정보가 없습니다"))

        val josaRo = JosaUtil.pick(dn.name, "로")
        pushLog("<D><b>${dn.name}</b></>${josaRo} 불가침 제의 서신을 보냈습니다.<1>${formatDate()}</>")

        services!!.diplomacyService.sendDiplomaticMessage(
            worldId = env.worldId,
            srcNationId = n.id,
            destNationId = dn.id,
            srcGeneralId = general.id,
            action = "non_aggression",
            extra = mapOf("year" to year, "month" to month)
        )

        return CommandResult(true, logs)
    }
}
