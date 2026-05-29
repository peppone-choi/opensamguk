package opensamguk.logic.actions.military

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit

/**
 * Shared 징병/모병 algorithm — faithful port of PHP `che_징병.php:21-236` (and `che_모병.php`, a 9-line
 * subclass differing ONLY in `costOffset` + default train/atmos). ONE class, instantiated twice (the
 * 농지개간 pattern).
 *
 *   - 징병: costOffset 1, default train/atmos Low (40/40)
 *   - 모병: costOffset 2, default train/atmos High (70/70)
 *
 * Crew/cost specifics (PHP grand truth):
 *   - maxCrew = getLeadership(true)*100 (getStatValue useFloor=true → truncated; PHP does NOT round
 *     through onCalcStat — unlike TS). When the requested crewType == the current crewType, maxCrew -= crew.
 *   - reqCrew(arg) = valueFit(amount, 100); appliedCrew = valueFit(amount, 100, maxCrew). Cost + run()
 *     both use the APPLIED (post-cap) crew (PHP `$this->maxCrew`).
 *   - getCost: reqGold = round(onCalcDomestic('징병','cost', costWithTech(tech, appliedCrew)) * costOffset)
 *     — costOffset BEFORE round. reqRice = round(onCalcDomestic('징병','rice', appliedCrew/100)).
 *   - train/atmos blend stores a RAW float (no round — FOLLOW PHP not TS) when same crewType & currCrew>0:
 *     train = (currCrew*train + appliedCrew*setTrain)/(currCrew+appliedCrew); else setTrain/setAtmos.
 *   - city: reqCrewDown = onCalcDomestic('징집인구','score', appliedCrew); newTrust =
 *     valueFit(trust - (reqCrewDown/pop)/costOffset*100, 0); pop -= reqCrewDown.
 *   - exp = ded = round(appliedCrew/100); addDex(crewType, appliedCrew/100, false); gold/rice floored at 0;
 *     leadership_exp += 1.
 *   - NONE draw from turn RNG; only the trailing unique lottery (out of scope here).
 *
 * `amount` arrives via the reserved arg map (`arg['amount']` / `arg['crewType']`). The constraints are
 * DEFINED in C-PURE/C-DEST and are exercised by MilitaryConstraintsTest; resolve here is the run() body.
 */
open class RecruitAlgorithm(
    private val pipeline: GeneralActionPipeline,
    override val name: String,          // "징병" | "모병" (NO space)
    private val costOffset: Int,        // 1 (징병) | 2 (모병)
    private val defaultTrain: Int,      // 40 (Low) | 70 (High)
    private val defaultAtmos: Int,      // 40 (Low) | 70 (High)
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key: String get() = "che_$name"
    override val category: String get() = "군사"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("crewType" to "int", "amount" to "int")

    /** che_징병.php:96-98 — getLeadership(true)*100 (truncated), minus current crew when crewType unchanged. */
    private fun maxCrewOf(general: General, reqCrewTypeId: Int): Int {
        val leadership = getStatValue(general, "leadership", pipeline, maxLevel,
            withInjury = true, useFloor = true).toInt()
        var maxCrew = leadership * 100
        if (reqCrewTypeId == general.crewTypeId) {
            maxCrew -= general.crew
        }
        return maxCrew
    }

    /** che_징병.php:107 — valueFit(amount, 100, maxCrew) (the APPLIED crew, post-cap). */
    private fun appliedCrew(general: General, reqCrewTypeId: Int, amount: Int): Int =
        valueFit(amount.toDouble(), 100.0, maxCrewOf(general, reqCrewTypeId).toDouble()).toInt()

    data class Cost(val gold: Int, val rice: Int)

    /** che_징병.php:140-154 — costOffset is applied to gold BEFORE round; rice = appliedCrew/100. */
    fun getCost(general: General, reqCrewType: UnitSetTable.UnitDetail, appliedCrew: Int, tech: Int): Cost {
        var reqGold = reqCrewType.costWithTech(tech, appliedCrew)
        reqGold = pipeline.onCalcDomestic(general, "징병", "cost", reqGold,
            mapOf("armType" to reqCrewType.armType))
        reqGold *= costOffset                                          // costOffset BEFORE round
        var reqRice = appliedCrew / 100.0
        reqRice = pipeline.onCalcDomestic(general, "징병", "rice", reqRice,
            mapOf("armType" to reqCrewType.armType))
        return Cost(phpRound(reqGold), phpRound(reqRice))
    }

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), occupiedCity(),
        reqCityCapacity("pop", "주민", GameConst.minAvailableRecruitPop + 100),
        reqCityTrust { _, _ -> 20.0 },
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val date = context.date

        val reqCrewTypeId = (context.args["crewType"] as? Number)?.toInt() ?: UnitSetTable.DEFAULT_CREWTYPE
        val amount = (context.args["amount"] as? Number)?.toInt() ?: 0
        val reqCrewType = UnitSetTable.byId(reqCrewTypeId) ?: error("unknown crewType $reqCrewTypeId")

        val tech = (d.nation?.tech ?: 0.0).toInt()

        val appliedCrew = appliedCrew(d.general, reqCrewTypeId, amount)
        val reqCrewText = numberFormat(appliedCrew)
        val crewTypeName = reqCrewType.name

        val currCrew = d.general.crew
        val currCrewTypeId = d.general.crewTypeId

        // onCalcDomestic train/atmos defaults (identity pipeline → the raw Low/High default).
        val setTrain = pipeline.onCalcDomestic(d.general, name, "train", defaultTrain.toDouble())
        val setAtmos = pipeline.onCalcDomestic(d.general, name, "atmos", defaultAtmos.toDouble())

        var g = d.general
        if (reqCrewTypeId == currCrewTypeId && currCrew > 0) {
            context.addLog("$crewTypeName <C>$reqCrewText</>명을 추가${name}했습니다. <1>$date</>")
            // RAW-float blend (NO round — FOLLOW PHP).
            val train = (currCrew * g.train + appliedCrew * setTrain) / (currCrew + appliedCrew)
            val atmos = (currCrew * g.atmos + appliedCrew * setAtmos) / (currCrew + appliedCrew)
            g = g.copy(crew = currCrew + appliedCrew, train = train, atmos = atmos)
        } else {
            context.addLog("$crewTypeName <C>$reqCrewText</>명을 ${name}했습니다. <1>$date</>")
            g = g.copy(crewTypeId = reqCrewTypeId, crew = appliedCrew,
                train = setTrain, atmos = setAtmos)
        }

        // city mutation
        val reqCrewDown = pipeline.onCalcDomestic(g, "징집인구", "score", appliedCrew.toDouble())
        val newTrust = valueFit(d.city.trust - (reqCrewDown / d.city.population) / costOffset * 100.0, 0.0)
        d.city = d.city.copy(trust = newTrust, population = d.city.population - reqCrewDown.toInt())

        val exp = phpRound(appliedCrew / 100.0)
        val ded = phpRound(appliedCrew / 100.0)

        // addDex(crewType, appliedCrew/100, affectTrainAtmos=false) — General.php:420-446.
        g = addDex(g, reqCrewType, appliedCrew / 100.0)

        val cost = getCost(g, reqCrewType, appliedCrew, tech)

        // increaseVar(experience, exp) / increaseVar(dedication, ded) — raw add, no per-add round.
        // gold/rice floored at 0; leadership_exp += 1; setAuxVar('armType', …).
        g = g.copy(
            experience = g.experience + exp,
            dedication = g.dedication + ded,
            gold = maxOf(0, g.gold - cost.gold),
            rice = maxOf(0, g.rice - cost.rice),
            meta = withMeta(g.meta,
                "leadership_exp" to metaDouble(g.meta, "leadership_exp") + 1,
                "aux" to withAux(g.meta, "armType" to reqCrewType.armType)),
            lastTurn = LastTurn(name, arg = linkedMapOf<String, Any?>("crewType" to reqCrewTypeId, "amount" to amount)),
        )
        d.general = g
    }

    /**
     * General.php:420-446 — addDex. armType CASTLE→SIEGE; <0 returns; WIZARD/SIEGE *0.9; affectTrainAtmos
     * scaling omitted (false here); onCalcStat('addDex') fold (identity under no modules); increaseVar(dex{armType}).
     */
    private fun addDex(general: General, crewType: UnitSetTable.UnitDetail, exp0: Double): General {
        var armType = crewType.armType
        if (armType == UnitSetTable.T_CASTLE) armType = UnitSetTable.T_SIEGE
        if (armType < 0) return general
        var exp = exp0
        if (armType == UnitSetTable.T_WIZARD || armType == UnitSetTable.T_SIEGE) exp *= 0.9
        exp = pipeline.onCalcStat(general, "addDex", exp, mapOf("armType" to armType))
        val dexKey = "dex$armType"
        return general.copy(meta = withMeta(general.meta, dexKey to metaDouble(general.meta, dexKey) + exp))
    }

    @Suppress("UNCHECKED_CAST")
    private fun withAux(meta: Map<String, Any?>, vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        val aux = LinkedHashMap((meta["aux"] as? Map<String, Any?>) ?: emptyMap())
        for ((k, v) in pairs) aux[k] = v
        return aux
    }

    companion object {
        fun cheJingbyeong(pipeline: GeneralActionPipeline, maxLevel: Int = 255) =
            RecruitAlgorithm(pipeline, "징병", costOffset = 1,
                defaultTrain = GameConst.defaultTrainLow, defaultAtmos = GameConst.defaultAtmosLow, maxLevel = maxLevel)

        fun cheMobyeong(pipeline: GeneralActionPipeline, maxLevel: Int = 255) =
            RecruitAlgorithm(pipeline, "모병", costOffset = 2,
                defaultTrain = GameConst.defaultTrainHigh, defaultAtmos = GameConst.defaultAtmosHigh, maxLevel = maxLevel)
    }
}
