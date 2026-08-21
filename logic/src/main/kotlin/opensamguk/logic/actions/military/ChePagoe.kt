package opensamguk.logic.actions.military

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.activeMapDestCity
import opensamguk.logic.constraints.disallowDiplomacyBetweenStatus
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notNeutralDestCity
import opensamguk.logic.constraints.notOccupiedDestCity
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.actions.GeneralRankIncrement
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.valueFit
import opensamguk.logic.world.CityConstRegistry
import kotlin.math.log2

/**
 * che_파괴 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_파괴.php`.
 *
 * 계략(sabotage) 커맨드. 적 dest 도시의 성벽(def)과 수비(wall)를 파괴한다.
 * che_화계의 자식 클래스로, statType='strength', injuryGeneral=true.
 *
 * argTest(che_화계.php:31-46): destCityID 존재 검증 → canonical {destCityID}.
 *
 * fullConditionConstraints(che_화계.php:138-149), PHP ORDER:
 *   [NotBeNeutral, OccupiedCity, SuppliedCity, NotOccupiedDestCity, NotNeutralDestCity,
 *    ReqGeneralGold, ReqGeneralRice, DisallowDiplomacyBetweenStatus([7])].
 *
 * minConditionConstraints(che_화계.php:119-125), PHP ORDER:
 *   [NotBeNeutral, OccupiedCity, SuppliedCity, ReqGeneralGold, ReqGeneralRice].
 *
 * run() 흐름 (che_화계.php:248-341):
 *   1. distance = searchDistance(actorCity, 5, false)[destCity] ?? 99
 *   2. prob = sabotageDefaultProb + attackProb - defenceProb
 *   3. prob /= distance; prob = valueFit(prob, 0, 0.5)
 *   4. DRAW1: rng.nextBool(prob) → false면 실패 분기 (골든 캡처 대상)
 *   5. 실패: 로그 + DRAW2(nextRangeInt 1,100) + DRAW3(nextRangeInt 1,70) + cost 차감 + exp/ded + stat_exp+1 + lastTurn
 *   6. 성공: SabotageInjury + affectDestCity(2 draws) + item + DRAW4/5 + cost + exp/ded + stat_exp+1 + rank+1 + lastTurn + staticEvent
 */
fun chePagoe(pipeline: GeneralActionPipeline, maxLevel: Int = 255): ChePagoe =
    ChePagoe(pipeline, maxLevel)

class ChePagoe(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key: String = "che_파괴"
    override val name: String = "파괴"
    override val category: String = "계략"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destCityID" to "int")

    /** che_화계.php:174-179 getCost — [develCost*5, develCost*5]. */
    fun getCost(env: opensamguk.logic.domain.WorldEnv): Pair<Int, Int> {
        val cost = env.develCost * 5
        return cost to cost
    }

    /** che_화계.php:31-46 argTest. */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("destCityID")) return null
        val destCityID = (raw["destCityID"] as? Number)?.toInt() ?: return null
        if (destCityID <= 0) return null
        return linkedMapOf("destCityID" to destCityID)
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> {
        val cost = (ctx.env["develCost"] as? Number)?.toInt()?.times(5) ?: 0
        return listOf(
            notBeNeutral(),
            occupiedCity(),
            suppliedCity(),
            reqGeneralGold { _, _ -> cost },
            reqGeneralRice { _, _ -> cost },
        )
    }

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val cost = (ctx.env["develCost"] as? Number)?.toInt()?.times(5) ?: 0
        return listOf(
            activeMapDestCity(), notBeNeutral(),
            occupiedCity(),
            suppliedCity(),
            notOccupiedDestCity(),
            notNeutralDestCity(),
            reqGeneralGold { _, _ -> cost },
            reqGeneralRice { _, _ -> cost },
            disallowDiplomacyBetweenStatus(linkedMapOf(7 to "불가침국입니다.")),
        )
    }

    /** che_화계.php:48-67 calcSabotageAttackProb — statType='strength' for 파괴. */
    private fun calcSabotageAttackProb(general: General): Double {
        val genScore = getStatValue(general, "strength", pipeline, maxLevel, withInjury = true, useFloor = true)
        var prob = genScore / GameConst.sabotageProbCoefByStat.toDouble()
        prob = pipeline.onCalcDomestic(general, "계략", "success", prob)
        return prob
    }

    /** che_화계.php:69-107 calcSabotageDefenceProb. */
    private fun calcSabotageDefenceProb(destCity: opensamguk.logic.domain.City, destGenerals: List<General>): Double {
        var maxGenScore = 0.0
        var probCorrection = 0.0
        var affectGeneralCount = 0
        for (dg in destGenerals) {
            if (dg.nationId != destCity.nationId) continue
            affectGeneralCount++
            val genScore = getStatValue(dg, "strength", pipeline, maxLevel, withInjury = true, useFloor = true)
            maxGenScore = maxOf(maxGenScore, genScore)
            probCorrection = pipeline.onCalcStat(dg, "sabotageDefence", probCorrection)
        }
        var prob = maxGenScore / GameConst.sabotageProbCoefByStat.toDouble()
        prob += probCorrection
        prob += (log2(affectGeneralCount + 1.0) - 1.25) * GameConst.sabotageDefenceCoefByGeneralCnt
        prob += destCity.security.toDouble() / destCity.securityMax / 5.0
        prob += if (destCity.supplyState != 0) 0.1 else 0.0
        return prob
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g = d.general
        val rng = context.rng
        val env = context.env

        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: return
        val destCity = d.destCity ?: return
        val destCityName = CityConstRegistry.of(context.env.mapName).byId(destCityId)?.name ?: ""
        val commandName = name
        val statType = "strength"

        val distance = context.cityDistance ?: 99

        // prob 계산 (che_화계.php:284-286)
        var prob = GameConst.sabotageDefaultProb + calcSabotageAttackProb(g) - calcSabotageDefenceProb(destCity, context.candidateGenerals)
        prob /= distance
        prob = valueFit(prob, 0.0, 0.5)

        // DRAW1: nextBool(prob) (che_화계.php:288)
        if (!rng.nextBool(prob)) {
            // 실패 분기 (che_화계.php:289-305)
            val josaYi = JosaUtil.pick(commandName, "이")
            context.addLog("<G><b>$destCityName</b></>에 $commandName$josaYi 실패했습니다. <1>${context.date}</>")

            // DRAW2: nextRangeInt(1, 100) (che_화계.php:292)
            val exp = rng.nextRangeInt(1, 100)
            // DRAW3: nextRangeInt(1, 70) (che_화계.php:293)
            val ded = rng.nextRangeInt(1, 70)

            val (reqGold, reqRice) = getCost(env)

            var resolvedGeneral = g.copy(
                gold = maxOf(0, g.gold - reqGold),
                rice = maxOf(0, g.rice - reqRice),
            )
            val experience = addExperience(resolvedGeneral, exp.toDouble(), pipeline)
            resolvedGeneral = experience.general
            experience.plainLog?.let { context.addPlainLog(it) }
            val dedication = addDedication(resolvedGeneral, ded.toDouble(), pipeline)
            resolvedGeneral = dedication.general
            dedication.plainLog?.let { context.addPlainLog(it) }
            resolvedGeneral = resolvedGeneral.copy(
                meta = withMeta(resolvedGeneral.meta, "${statType}_exp" to metaInt(resolvedGeneral.meta, "${statType}_exp") + 1),
                lastTurn = LastTurn(name, arg = linkedMapOf("destCityID" to destCityId)),
            )
            val statChange = checkStatChange(resolvedGeneral)
            d.general = statChange.general
            statChange.plainLogs.forEach { context.addPlainLog(it) }
            return
        }

        val destCityGeneralList = context.candidateGenerals.filter { it.nationId == destCity.nationId }
        val injuryCount = sabotageInjury(rng, destCityGeneralList, "계략", context, pipeline)

        val defAmount = maxOf(
            0,
            valueFit(
                rng.nextRangeInt(GameConst.sabotageDamageMin, GameConst.sabotageDamageMax).toDouble(),
                null,
                destCity.defense.toDouble(),
            ).toInt(),
        )
        val wallAmount = maxOf(
            0,
            valueFit(
                rng.nextRangeInt(GameConst.sabotageDamageMin, GameConst.sabotageDamageMax).toDouble(),
                null,
                destCity.wall.toDouble(),
            ).toInt(),
        )
        d.destCity = destCity.copy(
            defense = destCity.defense - defAmount,
            wall = destCity.wall - wallAmount,
            state = 32,
        )

        context.addGlobalActionLog("누군가가 <G><b>$destCityName</b></>의 성벽을 허물었습니다.")
        val josaYi = JosaUtil.pick(commandName, "이")
        context.addLog("<G><b>$destCityName</b></>에 $commandName$josaYi 성공했습니다. <1>${context.date}</>")
        context.addActionPlainLog(
            "도시의 수비가 <C>${numberFormat(defAmount)}</>, 성벽이 <C>${numberFormat(wallAmount)}</>만큼 감소하고, 장수 <C>$injuryCount</>명이 부상 당했습니다.",
        )

        consumeSabotageItemIfNeeded(context)

        val exp = rng.nextRangeInt(201, 300)
        val ded = rng.nextRangeInt(141, 210)

        val (reqGold, reqRice) = getCost(env)
        var postItemGeneral = d.general
        postItemGeneral = postItemGeneral.copy(
            gold = maxOf(0, postItemGeneral.gold - reqGold),
            rice = maxOf(0, postItemGeneral.rice - reqRice),
        )
        val experience = addExperience(postItemGeneral, exp.toDouble(), pipeline)
        postItemGeneral = experience.general
        experience.plainLog?.let { context.addPlainLog(it) }
        val dedication = addDedication(postItemGeneral, ded.toDouble(), pipeline)
        postItemGeneral = dedication.general
        dedication.plainLog?.let { context.addPlainLog(it) }
        postItemGeneral = postItemGeneral.copy(
            meta = withMeta(postItemGeneral.meta, "${statType}_exp" to metaInt(postItemGeneral.meta, "${statType}_exp") + 1),
            lastTurn = LastTurn(name, arg = linkedMapOf("destCityID" to destCityId)),
        )
        d.general = postItemGeneral
        d.rankIncrements.add(GeneralRankIncrement(g.id, "firenum", 1))
        StaticEventHandler.handleEvent(
            d.general,
            null,
            key,
            mapOf("year" to env.year, "startYear" to env.startYear, "develCost" to env.develCost),
            context.args,
        )
        val statChange = checkStatChange(d.general)
        d.general = statChange.general
        statChange.plainLogs.forEach { context.addPlainLog(it) }
    }
}
