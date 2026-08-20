package opensamguk.logic.actions.military

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst as CommonGameUnitConst
import opensamguk.common.constants.UnitCatalog
import opensamguk.common.constants.GameUnitDetail as CommonGameUnitDetail
import opensamguk.common.constants.UnitConstraint
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.actions.founding.GeneralUniqueLotteryIntent
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.CityConstVariant

object RecruitUnitAvailability {
    fun isValid(
        unit: CommonGameUnitDetail,
        general: General,
        ownCities: Map<Int, Int>,
        ownRegions: Set<Int>,
        relYear: Int,
        tech: Int,
        nationAux: Map<String, Any?>,
        cityConst: CityConstVariant,
    ): Boolean {
        for (constraint in unit.reqConstraints) {
            val ok = when (constraint) {
                is UnitConstraint.Impossible -> false
                is UnitConstraint.ReqTech -> tech >= constraint.reqTech
                is UnitConstraint.ReqCities ->
                    constraint.reqCities.any { name -> cityConst.byName(name)?.id?.let { ownCities.containsKey(it) } == true }
                // 보유 판정이다 — 국가가 가진 城 중 하나라도 그 키/지역이면 통과(인접은 보지 않는다).
                // 게이트 키(han 漢字 4축)와 기존 지역 라벨을 둘 다 본다. che 는 게이트 키가 비어
                // 있으므로 아래 regionIdByName 경로만 돈다 — 기존 동작 그대로.
                is UnitConstraint.ReqRegions ->
                    ownCities.keys.any { id -> cityConst.gateKeys(id).any(constraint.reqRegions::contains) } ||
                        constraint.reqRegions.any { name -> cityConst.regionIdByName(name)?.let { ownRegions.contains(it) } == true }
                // 주둔지 기준이다 — 소유가 아니라 지금 서 있는 땅을 본다.
                is UnitConstraint.ForbidRegions -> {
                    // 지역을 못 찾으면 막지 않는다 — 모르는 것을 금제로 바꾸지 않는다.
                    val here = cityConst.byId(general.cityId)?.region
                    val gate = cityConst.gateKeys(general.cityId)
                    constraint.forbidRegions.none { gate.contains(it) } &&
                        (here == null || constraint.forbidRegions.none { cityConst.regionIdByName(it) == here })
                }
                is UnitConstraint.ReqMinRelYear -> relYear >= constraint.reqMinRelYear
                is UnitConstraint.ReqChief -> general.officerLevel >= 5
                is UnitConstraint.ReqNotChief -> general.officerLevel < 5
                is UnitConstraint.ReqCitiesWithCityLevel ->
                    constraint.reqCities.any { name ->
                        cityConst.byName(name)?.id?.let { id -> (ownCities[id] ?: -1) >= constraint.reqCityLevel } == true
                    }
                is UnitConstraint.ReqHighLevelCities ->
                    ownCities.values.count { it >= constraint.reqCityLevel } >= constraint.reqCityCount
                is UnitConstraint.ReqNationAux ->
                    nationAuxCompare(
                        (nationAux[constraint.reqNationAuxKey] as? Number)?.toDouble() ?: 0.0,
                        constraint.cmp,
                        constraint.value,
                    )
            }
            if (!ok) return false
        }
        return true
    }

    private fun nationAuxCompare(lhs: Double, cmp: String, rhs: Double): Boolean = when (cmp) {
        "==" -> lhs == rhs
        "!=" -> lhs != rhs
        "<=" -> lhs <= rhs
        ">=" -> lhs >= rhs
        "<" -> lhs < rhs
        ">" -> lhs > rhs
        else -> false
    }
}

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

    var lastUniqueLotteryIntent: GeneralUniqueLotteryIntent? = null
        private set

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        val crewType = raw["crewType"] as? Int ?: return emptyMap()
        if (crewType < 1000) return emptyMap()
        if (UnitSetTable.byId(crewType) == null) return emptyMap()
        val amountNumber = when (val amount = raw["amount"]) {
            is Number -> amount.toDouble()
            is String -> amount.toDoubleOrNull()
            else -> null
        } ?: return emptyMap()
        if (!amountNumber.isFinite()) return emptyMap()
        val amount = amountNumber.toInt()
        if (amount < 0) return emptyMap()
        return linkedMapOf("crewType" to crewType, "amount" to amount)
    }

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

    private fun requestedCrew(amount: Int): Int =
        valueFit(amount.toDouble(), 100.0).toInt()

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

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), recruitableCity(),
        reqCityCapacity("pop", "주민", GameConst.minAvailableRecruitPop + 100),
        reqCityTrust { _, _ -> 20.0 },
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val reqCrewTypeId = (ctx.args["crewType"] as? Number)?.toInt() ?: CommonGameUnitConst.DEFAULT_CREWTYPE
        val amount = (ctx.args["amount"] as? Number)?.toInt() ?: 0
        val reqCrew = requestedCrew(amount)

        return listOf(
            notBeNeutral(),
            recruitableCity(),
            reqCityCapacity("pop", "주민", GameConst.minAvailableRecruitPop + reqCrew),
            reqCityTrust { _, _ -> 20.0 },
            reqGeneralGold { c, view -> costForConstraint(c, view, reqCrewTypeId, amount).gold },
            reqGeneralRice { c, view -> costForConstraint(c, view, reqCrewTypeId, amount).rice },
            reqGeneralCrewMargin(
                reqCrewTypeId,
                curCrewTypeId = { c, view -> (view.get(RequirementKey.General(c.actorId)) as? General)?.crewTypeId ?: 0 },
                leadership = { c, view ->
                    val g = view.get(RequirementKey.General(c.actorId)) as? General
                    if (g == null) 0 else getStatValue(g, "leadership", pipeline, maxLevel, withInjury = true, useFloor = true).toInt()
                },
            ),
            crewTypeConstraint(reqCrewTypeId),
        )
    }

    fun crewTypeAvailability(
        ctx: ConstraintContext,
        view: StateView,
        crewTypeId: Int,
    ): ConstraintResult = crewTypeConstraint(crewTypeId).test(ctx, view)

    private fun crewTypeConstraint(crewTypeId: Int): Constraint =
        availableRecruitCrewType(crewTypeId) { c, view -> recruitableUnit(c, view, crewTypeId) }

    private fun costForConstraint(
        ctx: ConstraintContext,
        view: StateView,
        reqCrewTypeId: Int,
        amount: Int,
    ): Cost {
        val general = view.get(RequirementKey.General(ctx.actorId)) as? General ?: return Cost(Int.MAX_VALUE, Int.MAX_VALUE)
        val unitSet = activeUnitSet(ctx)
        if (!UnitSetTable.isSupported(unitSet)) return Cost(0, 0)
        val reqCrewType = UnitSetTable.byId(unitSet, reqCrewTypeId) ?: return Cost(Int.MAX_VALUE, Int.MAX_VALUE)
        val nation = view.get(RequirementKey.Nation(ctx.nationId ?: general.nationId)) as? Nation
        val appliedCrew = appliedCrew(general, reqCrewTypeId, amount)
        return getCost(general, reqCrewType, appliedCrew, (nation?.tech ?: 0.0).toInt())
    }

    private fun recruitableUnit(ctx: ConstraintContext, view: StateView, reqCrewTypeId: Int): Boolean {
        val general = view.get(RequirementKey.General(ctx.actorId)) as? General ?: return false
        val nation = view.get(RequirementKey.Nation(ctx.nationId ?: general.nationId)) as? Nation ?: return false
        val unitSet = activeUnitSet(ctx) ?: UnitSetTable.CHE_UNIT_SET
        if (UnitSetTable.byId(unitSet, reqCrewTypeId) == null) return false
        val unit = UnitCatalog.byId(unitSet, reqCrewTypeId) ?: return false
        val cityConst = CityConstRegistry.find(activeMapName(ctx)) ?: return false
        val ownCities = ownedCityLevels(ctx, view, general, nation)
        val ownRegions = ownedRegions(ctx, view, ownCities.keys, cityConst)
        val relYear = relYear(ctx)
        val nationAux = stringAnyMap(nation.meta["aux"])
        return RecruitUnitAvailability.isValid(unit, general, ownCities, ownRegions, relYear, nation.tech.toInt(), nationAux, cityConst)
    }

    private fun activeUnitSet(ctx: ConstraintContext): String? = when {
        "unitSet" !in ctx.env -> null
        else -> ctx.env["unitSet"] as? String ?: "__invalid_unit_set__"
    }

    private fun activeMapName(ctx: ConstraintContext): String = when {
        "mapName" !in ctx.env -> CityConstRegistry.DEFAULT_MAP_NAME
        else -> ctx.env["mapName"] as? String ?: "__invalid_map_name__"
    }

    private fun ownedCityLevels(
        ctx: ConstraintContext,
        view: StateView,
        general: General,
        nation: Nation,
    ): Map<Int, Int> {
        val envOwned = intIntMap(ctx.env["ownCities"])
            ?: intIntMap(ctx.env["ownedCities"])
            ?: intIntMap(ctx.env["ownedCityLevels"])
        if (envOwned != null) return envOwned

        val currentCity = view.get(RequirementKey.City(ctx.cityId ?: general.cityId)) as? City
        if (currentCity?.nationId == nation.id) return linkedMapOf(currentCity.id to currentCity.level)
        return emptyMap()
    }

    private fun ownedRegions(
        ctx: ConstraintContext,
        view: StateView,
        ownedCityIds: Set<Int>,
        cityConst: CityConstVariant,
    ): Set<Int> {
        val envRegions = intSet(ctx.env["ownRegions"])
            ?: intSet(ctx.env["ownedRegions"])
        if (envRegions != null) return envRegions

        val out = LinkedHashSet<Int>()
        for (cityId in ownedCityIds) {
            cityConst.byId(cityId)?.let { out.add(it.region) }
        }
        return out
    }

    private fun relYear(ctx: ConstraintContext): Int {
        (ctx.args["relYear"] as? Number)?.toInt()?.let { return maxOf(0, it) }
        (ctx.env["relYear"] as? Number)?.toInt()?.let { return maxOf(0, it) }
        val year = (ctx.env["year"] as? Number)?.toInt()
        val startYear = (ctx.env["startYear"] as? Number)?.toInt()
            ?: (ctx.env["startyear"] as? Number)?.toInt()
        return maxOf(0, (year ?: 0) - (startYear ?: 0))
    }

    private fun intIntMap(value: Any?): Map<Int, Int>? {
        val map = value as? Map<*, *> ?: return null
        val out = LinkedHashMap<Int, Int>()
        for ((k, v) in map) {
            val key = (k as? Number)?.toInt() ?: (k as? String)?.toIntOrNull() ?: continue
            val level = (v as? Number)?.toInt() ?: (v as? String)?.toIntOrNull() ?: continue
            out[key] = level
        }
        return out
    }

    private fun intSet(value: Any?): Set<Int>? {
        val raw = value ?: return null
        val items = when (raw) {
            is Iterable<*> -> raw
            is Array<*> -> raw.asIterable()
            else -> return null
        }
        val out = LinkedHashSet<Int>()
        for (item in items) {
            val n = (item as? Number)?.toInt() ?: (item as? String)?.toIntOrNull() ?: continue
            out.add(n)
        }
        return out
    }

    private fun stringAnyMap(value: Any?): Map<String, Any?> {
        val raw = value as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in raw) {
            val key = k as? String ?: continue
            out[key] = v
        }
        return out
    }

    override fun resolve(context: GeneralActionResolveContext) {
        lastUniqueLotteryIntent = null
        val d = context.draft
        val date = context.date

        val reqCrewTypeId = (context.args["crewType"] as? Number)?.toInt() ?: CommonGameUnitConst.DEFAULT_CREWTYPE
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
        g = addDexForUnit(pipeline, g, reqCrewType, appliedCrew / 100.0)

        val cost = getCost(g, reqCrewType, appliedCrew, tech)

        val experience = addExperience(g, exp.toDouble(), pipeline)
        g = experience.general
        experience.plainLog?.let { context.addPlainLog(it) }
        val dedication = addDedication(g, ded.toDouble(), pipeline)
        g = dedication.general
        dedication.plainLog?.let { context.addPlainLog(it) }

        g = g.copy(
            gold = maxOf(0, g.gold - cost.gold),
            rice = maxOf(0, g.rice - cost.rice),
            meta = withMeta(g.meta,
                "leadership_exp" to metaDouble(g.meta, "leadership_exp") + 1),
            lastTurn = LastTurn(name, arg = linkedMapOf<String, Any?>("crewType" to reqCrewTypeId, "amount" to amount)),
        )
        val statRes = checkStatChange(g)
        statRes.plainLogs.forEach { context.addPlainLog(it) }
        d.general = statRes.general
        StaticEventHandler.handleEvent(d.general, d.destGeneral, rawClassName, emptyMap(), context.args)
        d.general = d.general.copy(meta = withMeta(d.general.meta, "aux" to withAux(d.general.meta, "armType" to reqCrewType.armType)))
        lastUniqueLotteryIntent = GeneralUniqueLotteryIntent(
            generalId = d.general.id,
            year = context.env.year,
            month = context.month,
            seedReason = lotteryActionName,
            acquireType = "아이템",
            afterTail = "setResultTurn>checkStatChange>StaticEventHandler>setAux",
        )
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
