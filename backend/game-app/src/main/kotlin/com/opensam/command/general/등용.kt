package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 등용(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "등용"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val constraints = mutableListOf(
                ReqEnvValue("join_mode", "!=", "onlyRandom", "랜덤 임관만 가능합니다"),
                NotBeNeutral(),
                OccupiedCity(),
                SuppliedCity(),
                ExistsDestGeneral(),
                DifferentNationDestGeneral(),
                ReqGeneralGold(getCost().gold),
            )
            if (destGeneral?.officerLevel == 12) {
                constraints.add(AlwaysFail("군주에게는 등용장을 보낼 수 없습니다."))
            }
            return constraints
        }

    override val minConditionConstraints = listOf(
        ReqEnvValue("join_mode", "!=", "onlyRandom", "랜덤 임관만 가능합니다"),
        NotBeNeutral(),
        OccupiedCity(),
        SuppliedCity(),
    )

    override fun getCost(): CommandCost {
        val develCost = env.develCost
        val dg = destGeneral
        if (dg == null) return CommandCost(gold = develCost)
        // PHP: round(develcost + (exp + ded) / 1000) * 10
        val reqGold = (kotlin.math.round(develCost + (dg.experience + dg.dedication) / 1000.0) * 10).toInt()
        return CommandCost(gold = reqGold)
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destName = destGeneral?.name ?: "알 수 없음"
        val cost = getCost()

        pushLog("<Y>${destName}</>에게 등용 권유 서신을 보냈습니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"gold":${-cost.gold},"experience":100,"dedication":200,"leadershipExp":1},"scoutMessage":{"fromGeneralId":"${general.id}","toGeneralId":"${arg?.get("destGeneralID")}"}}"""
        )
    }
}
