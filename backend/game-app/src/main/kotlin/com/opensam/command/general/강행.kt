package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.math.max
import kotlin.random.Random

private const val STAT_DECREASE = 5
private const val MIN_STAT = 20

class 강행(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "강행"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotSameDestCity(),
                NearCity(3),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
            )
        }

    override fun getCost() = CommandCost(gold = env.develCost * 5, rice = 0)

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val destCityName = destCity?.name ?: "알 수 없음"
        val destCityId = destCity?.id ?: 0L
        val josaRo = JosaUtil.pick(destCityName, "로")

        pushLog("<G><b>${destCityName}</b></>${josaRo} 강행했습니다. <1>$date</>")

        val exp = 100
        val cost = getCost()
        val newTrain = max(MIN_STAT, general.train.toInt() - STAT_DECREASE)
        val newAtmos = max(MIN_STAT, general.atmos.toInt() - STAT_DECREASE)
        val trainDelta = newTrain - general.train.toInt()
        val atmosDelta = newAtmos - general.atmos.toInt()

        // Legacy parity: 방랑군(nation.level==0) 군주(officerLevel==12)가 강행하면
        // 같은 세력의 모든 장수를 함께 이동시킨다.
        val isWanderingLord = general.officerLevel.toInt() == 12 && nation?.level?.toInt() == 0
        val wanderingJson = if (isWanderingLord) {
            ""","wanderingNationMove":{"destCityId":"$destCityId","nationId":"${general.nationId}","destCityName":"$destCityName","logMessage":"방랑군 세력이 <G><b>${destCityName}</b></>${josaRo} 강행했습니다."}"""
        } else ""

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"cityId":"$destCityId","gold":${-cost.gold},"train":$trainDelta,"atmos":$atmosDelta,"experience":$exp,"leadershipExp":1}$wanderingJson}"""
        )
    }
}
