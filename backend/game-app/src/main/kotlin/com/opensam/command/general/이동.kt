package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.random.Random

private const val ATMOS_DECREASE_ON_MOVE = 5
private const val MIN_ATMOS = 20

class 이동(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "이동"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotSameDestCity(),
                NearCity(1),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
            )
        }

    override val minConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
            )
        }

    override fun getCost() = CommandCost(gold = env.develCost, rice = 0)

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destCityName = destCity?.name ?: "알 수 없음"
        val destCityId = destCity?.id ?: 0L

        // Legacy PHP uses JosaUtil for 로/으로
        pushLog("<G><b>${destCityName}</b></>(으)로 이동했습니다. <1>$date</>")

        val exp = 50
        val cost = getCost()
        val newAtmos = max(MIN_ATMOS, general.atmos.toInt() - ATMOS_DECREASE_ON_MOVE)
        val atmosDelta = newAtmos - general.atmos.toInt()

        // Legacy PHP: if officer_level==12 and nation.level==0 (roaming), move all nation generals
        val isRoamingLeader = general.officerLevel.toInt() == 12 && (nation?.level?.toInt() ?: 1) == 0
        val roamingMoveJson = if (isRoamingLeader) {
            ""","roamingMove":{"nationId":${nation?.id ?: 0},"destCityId":"$destCityId","destCityName":"$destCityName"}"""
        } else ""

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"cityId":"$destCityId","gold":${-cost.gold},"atmos":$atmosDelta,"experience":$exp,"leadershipExp":1},"tryUniqueLottery":true$roamingMoveJson}"""
        )
    }
}
