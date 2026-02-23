package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private const val DEFAULT_TRAIN_HIGH = 70
private const val DEFAULT_ATMOS_HIGH = 70
private const val MIN_RECRUIT_AMOUNT = 100
private const val COST_OFFSET = 2

class che_모병(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "모병"

    private val reqCrewTypeId: Int
        get() = (arg?.get("crewType") as? Number)?.toInt() ?: 0

    private val reqAmount: Int
        get() = maxOf(MIN_RECRUIT_AMOUNT, (arg?.get("amount") as? Number)?.toInt() ?: 0)

    private val maxCrew: Int
        get() {
            val leadership = general.leadership.toInt()
            var max = leadership * 100
            if (reqCrewTypeId == general.crewType.toInt()) {
                max -= general.crew
            }
            return minOf(reqAmount, maxOf(0, max))
        }

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                OccupiedCity(),
                ReqCityCapacity("pop", "주민", env.minAvailableRecruitPop + reqAmount),
                ReqCityTrust(20.toFloat()),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
                ReqGeneralCrewMargin(reqCrewTypeId),
                AvailableRecruitCrewType(reqCrewTypeId),
            )
        }

    override val minConditionConstraints: List<Constraint>
        get() = listOf(
            NotBeNeutral(),
            OccupiedCity(),
            ReqCityCapacity("pop", "주민", env.minAvailableRecruitPop + 100),
            ReqCityTrust(20.toFloat()),
        )

    override fun getCost(): CommandCost {
        val mc = maxCrew
        val baseCost = mc / 10
        val reqGold = (baseCost * COST_OFFSET)
        val reqRice = mc / 100
        return CommandCost(gold = reqGold, rice = reqRice)
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0
    override fun getDuration() = 300

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val reqCrew = maxCrew
        val crewTypeId = reqCrewTypeId
        val currCrew = general.crew
        val currCrewTypeId = general.crewType.toInt()

        val newCrew: Int
        val newTrain: Int
        val newAtmos: Int
        val logMessage: String

        if (crewTypeId == currCrewTypeId && currCrew > 0) {
            logMessage = "병사 ${reqCrew}명을 추가모병했습니다. $date"
            newTrain = (currCrew * general.train + reqCrew * DEFAULT_TRAIN_HIGH) / (currCrew + reqCrew)
            newAtmos = (currCrew * general.atmos + reqCrew * DEFAULT_ATMOS_HIGH) / (currCrew + reqCrew)
            newCrew = currCrew + reqCrew
        } else {
            logMessage = "병사 ${reqCrew}명을 모병했습니다. $date"
            newCrew = reqCrew
            newTrain = DEFAULT_TRAIN_HIGH
            newAtmos = DEFAULT_ATMOS_HIGH
        }
        pushLog(logMessage)

        val cost = getCost()
        val exp = reqCrew / 100
        val ded = reqCrew / 100
        val popLoss = -reqCrew
        val dexGain = reqCrew / 100

        // Trust reduction: (recruitPop / cityPop / costOffset) * 100
        val cityPop = city?.pop ?: 10000
        val trustLoss = if (cityPop > 0) (reqCrew.toDouble() / cityPop / COST_OFFSET * 100) else 0.0

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"crew":${newCrew - currCrew},"crewType":$crewTypeId,"train":${newTrain - general.train},"atmos":${newAtmos - general.atmos},"gold":${-cost.gold},"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"leadershipExp":1},"cityChanges":{"pop":$popLoss,"trustLoss":$trustLoss},"dexChanges":{"crewType":$crewTypeId,"amount":$dexGain}}"""
        )
    }
}
