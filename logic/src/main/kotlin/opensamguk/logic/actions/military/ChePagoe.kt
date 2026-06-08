package opensamguk.logic.actions.military

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
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
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.valueFit
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
 *   6. 성공: SabotageInjury(미포팅) + affectDestCity(2 draws) + item + DRAW4/5 + cost + exp/ded + stat_exp+1 + rank+1 + lastTurn + staticEvent
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
        if (CityConst.byId(destCityID) == null) return null
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
            notBeNeutral(),
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
        val destCityName = CityConst.byId(destCityId)?.name ?: ""
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

            d.general = g.copy(
                gold = maxOf(0, g.gold - reqGold),
                rice = maxOf(0, g.rice - reqRice),
                experience = g.experience + exp,
                dedication = g.dedication + ded,
                meta = withMeta(g.meta, "${statType}_exp" to metaInt(g.meta, "${statType}_exp") + 1),
                lastTurn = LastTurn(name, arg = linkedMapOf("destCityID" to destCityId)),
            )
            return
        }

        // 성공 분기 — 골든 미캡처, SabotageInjury 서브시스템 미포팅으로 quarantine
        // TODO(백로그): che_화계.php:308-340 성공 경로 전체 포팅 (SabotageInjury + affectDestCity + item + exp/ded + rank)
    }
}
