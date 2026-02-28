package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.model.CrewType
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 징병 command — recruit soldiers.
 *
 * Legacy parity: che_징병.php
 * - Gold cost = crewType.cost * getTechCost(tech) * maxCrew / 100 * costOffset
 * - Rice cost = maxCrew / 100 (with onCalcDomestic modifier)
 * - Trust loss = (reqCrewDown / cityPop) / costOffset * 100
 * - Pop loss = reqCrewDown (= maxCrew, modified by onCalcDomestic '징집인구')
 * - Default train/atmos: 40/40 (징병), 70/70 (모병)
 */
open class che_징병(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "징병"

    protected open val costOffset: Int = 1
    protected open val defaultTrain: Int = DEFAULT_TRAIN_LOW
    protected open val defaultAtmos: Int = DEFAULT_ATMOS_LOW

    protected val reqCrewTypeId: Int
        get() = (arg?.get("crewType") as? Number)?.toInt() ?: 0

    protected val reqCrewType: CrewType?
        get() = CrewType.fromCode(reqCrewTypeId)

    private val reqAmount: Int
        get() = maxOf(MIN_RECRUIT_AMOUNT, (arg?.get("amount") as? Number)?.toInt() ?: 0)

    protected val maxCrew: Int
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

    /**
     * Legacy parity: costWithTech(tech, maxCrew) = crewType.cost * getTechCost(tech) * maxCrew / 100
     * Then apply onCalcDomestic modifier for 'cost', multiply by costOffset.
     * Rice = maxCrew / 100 with onCalcDomestic modifier for 'rice'.
     */
    override fun getCost(): CommandCost {
        val mc = maxCrew
        val crewType = reqCrewType
        val techCost = getNationTechCost()

        // Legacy compatibility: tests may pass arm type indices (0..5) instead of CrewType code.
        // Fallback base unit cost=10 when crew type lookup is unavailable.
        val unitCost = crewType?.cost?.toDouble() ?: 10.0

        // Legacy: costWithTech(tech, maxCrew) = unit.cost * getTechCost(tech) * crew / 100
        var reqGold = unitCost * techCost * mc / 100.0
        // Legacy: onCalcDomestic('징병', 'cost', reqGold, ['armType' => armType])
        reqGold = DomesticUtils.applyModifier(services, general, nation, "징병", "cost", reqGold)
        reqGold *= costOffset

        var reqRice = mc / 100.0
        // Legacy: onCalcDomestic('징병', 'rice', reqRice, ['armType' => armType])
        reqRice = DomesticUtils.applyModifier(services, general, nation, "징병", "rice", reqRice)

        return CommandCost(
            gold = reqGold.roundToInt(),
            rice = reqRice.roundToInt()
        )
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

        val crewTypeName = getCrewTypeName(crewTypeId) ?: "병사"
        val reqCrewText = String.format("%,d", reqCrew)

        val newCrew: Int
        val newTrain: Int
        val newAtmos: Int
        val logMessage: String

        if (crewTypeId == currCrewTypeId && currCrew > 0) {
            logMessage = "${crewTypeName} <C>${reqCrewText}</>명을 추가${actionName}했습니다. <1>$date</>"
            newTrain = (currCrew * general.train + reqCrew * defaultTrain) / (currCrew + reqCrew)
            newAtmos = (currCrew * general.atmos + reqCrew * defaultAtmos) / (currCrew + reqCrew)
            newCrew = currCrew + reqCrew
        } else {
            logMessage = "${crewTypeName} <C>${reqCrewText}</>명을 ${actionName}했습니다. <1>$date</>"
            newCrew = reqCrew
            newTrain = defaultTrain
            newAtmos = defaultAtmos
        }
        pushLog(logMessage)

        val cost = getCost()
        val exp = reqCrew / 100
        val ded = reqCrew / 100

        // Legacy: reqCrewDown = onCalcDomestic('징집인구', 'score', reqCrew)
        val reqCrewDown = DomesticUtils.applyModifier(services, general, nation, "징집인구", "score", reqCrew.toDouble()).roundToInt()

        // Legacy: trust -= (reqCrewDown / cityPop) / costOffset * 100
        val cityPop = city?.pop ?: 10000
        val trustLoss = if (cityPop > 0) {
            (reqCrewDown.toDouble() / cityPop) / costOffset * 100.0
        } else {
            0.0
        }
        // Clamp trust to >= 0
        val currentTrust = city?.trust?.toDouble() ?: 50.0
        val clampedTrustLoss = trustLoss.coerceAtMost(currentTrust)

        // Resolve armType code for dex changes (legacy: armType maps to dex1-5)
        val crewType = reqCrewType
        val armTypeCode = crewType?.armType?.code ?: 0

        val dexGain = reqCrew / 100

        return CommandResult(
            success = true,
            logs = logs,
            message = buildString {
                append("""{"statChanges":{""")
                append(""""crew":${newCrew - currCrew},"crewType":$crewTypeId""")
                append(""","train":${newTrain - general.train},"atmos":${newAtmos - general.atmos}""")
                append(""","gold":${-cost.gold},"rice":${-cost.rice}""")
                append(""","experience":$exp,"dedication":$ded,"leadershipExp":1""")
                append("""},"cityChanges":{"pop":${-reqCrewDown},"trust":${-clampedTrustLoss.roundToInt()}}""")
                append(""","dexChanges":{"crewType":$armTypeCode,"amount":$dexGain}""")
                append(""","tryUniqueLottery":true""")
                append(""","auxVarChanges":{"armType":$armTypeCode}""")
                append("""}""")
            }
        )
    }

    companion object {
        const val DEFAULT_TRAIN_LOW = 40
        const val DEFAULT_ATMOS_LOW = 40
        const val MIN_RECRUIT_AMOUNT = 100
        const val MIN_AVAILABLE_RECRUIT_POP = 3000
        const val MIN_TRUST_FOR_RECRUIT = 20
    }
}
