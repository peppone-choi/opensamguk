package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

private const val DEFAULT_TRAIN_LOW = 50
private const val DEFAULT_ATMOS_LOW = 50
private const val MIN_RECRUIT_AMOUNT = 100
private const val COST_OFFSET = 1
private const val MIN_AVAILABLE_RECRUIT_POP = 30000
private const val MIN_TRUST_FOR_RECRUIT = 20

class che_징병(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "징병"

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
            val mc = maxCrew
            return listOf(
                NotBeNeutral(),
                OccupiedCity(),
                ReqCityCapacity("pop", "주민", MIN_AVAILABLE_RECRUIT_POP + mc),
                ReqCityTrust(MIN_TRUST_FOR_RECRUIT.toFloat()),
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
            ReqCityCapacity("pop", "주민", MIN_AVAILABLE_RECRUIT_POP + MIN_RECRUIT_AMOUNT),
            ReqCityTrust(MIN_TRUST_FOR_RECRUIT.toFloat()),
        )

    override fun getCost(): CommandCost {
        val mc = maxCrew
        val techCost = getNationTechCost()
        val baseCost = (mc / 100.0 * techCost).roundToInt()
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

        // Resolve crew type name from env or fallback
        val crewTypeName = getCrewTypeName(crewTypeId) ?: "병사"
        val reqCrewText = String.format("%,d", reqCrew)

        val newCrew: Int
        val newTrain: Int
        val newAtmos: Int
        val logMessage: String

        if (crewTypeId == currCrewTypeId && currCrew > 0) {
            logMessage = "${crewTypeName} <C>${reqCrewText}</>명을 추가징병했습니다. <1>$date</>"
            newTrain = (currCrew * general.train + reqCrew * DEFAULT_TRAIN_LOW) / (currCrew + reqCrew)
            newAtmos = (currCrew * general.atmos + reqCrew * DEFAULT_ATMOS_LOW) / (currCrew + reqCrew)
            newCrew = currCrew + reqCrew
        } else {
            logMessage = "${crewTypeName} <C>${reqCrewText}</>명을 징병했습니다. <1>$date</>"
            newCrew = reqCrew
            newTrain = DEFAULT_TRAIN_LOW
            newAtmos = DEFAULT_ATMOS_LOW
        }
        pushLog(logMessage)

        val cost = getCost()
        val exp = reqCrew / 100
        val ded = reqCrew / 100

        // Legacy: trust loss from recruitment
        // trustLoss = (recruitPop / cityPop) / costOffset * 100
        // popLoss = recruitPop (same as reqCrew after onCalcDomestic)
        val popLoss = -reqCrew

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"crew":${newCrew - currCrew},"crewType":$crewTypeId,"train":${newTrain - general.train},"atmos":${newAtmos - general.atmos},"gold":${-cost.gold},"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"leadershipExp":1},"cityChanges":{"pop":$popLoss,"trustLoss":true}}"""
        )
    }
}
