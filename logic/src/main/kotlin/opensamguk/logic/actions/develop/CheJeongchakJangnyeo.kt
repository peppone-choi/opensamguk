package opensamguk.logic.actions.develop

import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notWanderingNation
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.criticalRatioDomestic
import opensamguk.logic.domestic.criticalScoreEx
import opensamguk.logic.domestic.getDomesticExpLevelBonus
import opensamguk.logic.domestic.updateMaxDomesticCritical
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.clamp
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit

/**
 * che_정착장려 — PHP che_정착장려.php. A leadership-stat develop command that is NOT a che_상업투자
 * subclass (calcBaseScore has NO trust factor; run() has NO `if(trust<80)` adjustment).
 *
 *  - statKey='leadership', cityKey='pop' → mutates city.population (clamp [0, pop_max]).
 *  - getCost: develCost*2 as RICE (gold=0); ReqGeneralRice gate + RemainCityCapacity('pop','정착 장려').
 *  - score ROUNDED; exp=round*0.7, ded=round*1.0; updateMaxDomesticCritical(round) | reset.
 *  - score *= 10 AFTER exp/ded + maxCrit; city.pop += score.
 *  - log: `정착 장려{을} … 주민이 <C>{number_format(score*10,0)}</>명 증가했습니다.`
 *  - increaseVar('leadership_exp', 1). NO front-debuff.
 */
class CheJeongchakJangnyeo(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key: String = "che_정착장려"
    override val name: String = "정착 장려"
    private val actionKey = "인구"
    private val statKey = "leadership"

    /** PHP getCost: reqGold=0, reqRice=round(onCalcDomestic('인구','cost', develCost*2)). */
    fun getReqRice(general: General, env: WorldEnv): Int =
        phpRound(pipeline.onCalcDomestic(general, actionKey, "cost", (env.develCost * 2).toDouble()))

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), notWanderingNation(), occupiedCity(), suppliedCity(),
        reqGeneralGold { _, _ -> 0 },
        reqGeneralRice { c, view -> getReqRice(view.get(RequirementKey.General(c.actorId)) as General, envOf(c)) },
        remainCityPopCapacity(),
    )

    /** RemainCityCapacity('pop', '정착 장려') — generic over city.pop / pop_max (PHP RemainCityCapacity.php). */
    private fun remainCityPopCapacity() = object : Constraint {
        override val name = "RemainCityCapacity"
        override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
        override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
            val cid = ctx.cityId ?: (view.get(RequirementKey.General(ctx.actorId)) as? General)?.cityId ?: 0
            val c = view.get(RequirementKey.City(cid)) as? City ?: return ConstraintResult.Unknown(requires(ctx))
            if (c.population < c.populationMax) return ConstraintResult.Allow
            val josaUn = JosaUtil.pick(name, "은")
            return ConstraintResult.Deny("$name$josaUn 충분합니다.")
        }
    }

    private fun calcBaseScore(d: GeneralActionDraft, rng: RandUtil): Double {
        var score = getStatValue(d.general, "leadership", pipeline, maxLevel, withInjury = true, useFloor = false)
        score *= getDomesticExpLevelBonus(metaInt(d.general.meta, "explevel"))
        score *= rng.nextRange(0.8, 1.2)                                          // DRAW 1
        return pipeline.onCalcDomestic(d.general, actionKey, "score", score)
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft; val rng = context.rng; val env = context.env
        val reqRice = getReqRice(d.general, env)
        var score = valueFit(calcBaseScore(d, rng), 1.0)

        val ratio = criticalRatioDomestic(d.general, statKey, pipeline, maxLevel)
        var successRatio = ratio.success; var failRatio = ratio.fail
        // NOTE: 정착장려 has NO `if(trust<80)` adjustment (che_정착장려.php:130-133).
        successRatio = pipeline.onCalcDomestic(d.general, actionKey, "success", successRatio)
        failRatio = pipeline.onCalcDomestic(d.general, actionKey, "fail", failRatio)
        successRatio = clamp(successRatio, 0.0, 1.0)
        failRatio = clamp(failRatio, 0.0, 1.0 - successRatio)
        val normalRatio = 1.0 - failRatio - successRatio

        val pick = rng.choiceUsingWeight(linkedMapOf(                              // DRAW 2
            "fail" to failRatio, "success" to successRatio, "normal" to normalRatio))
        val scoreD = score * criticalScoreEx(rng, pick)                           // DRAW 3
        val roundedScore = phpRound(scoreD)

        val exp = roundedScore * 0.7
        val ded = roundedScore * 1.0

        val expKey = "${statKey}_exp"
        val nextMeta = if (pick == "success") {
            val nextAux = updateMaxDomesticCritical(metaDouble(d.general.meta, "max_domestic_critical"), roundedScore)
            withMeta(d.general.meta, expKey to metaInt(d.general.meta, expKey) + 1,
                     "max_domestic_critical" to nextAux)
        } else {
            withMeta(d.general.meta, expKey to metaInt(d.general.meta, expKey) + 1,
                     "max_domestic_critical" to 0)
        }

        // score *= 10 (AFTER exp/ded + maxCrit).
        val popScore = roundedScore * 10

        val scoreText = numberFormat(popScore)
        val josaUl = JosaUtil.pick(name, "을")
        val log = when (pick) {
            "fail"    -> "$name$josaUl <span class='ev_failed'>실패</span>하여 주민이 <C>$scoreText</>명 증가했습니다. <1>${context.date}</>"
            "success" -> "$name$josaUl <S>성공</>하여 주민이 <C>$scoreText</>명 증가했습니다. <1>${context.date}</>"
            else      -> "$name$josaUl 하여 주민이 <C>$scoreText</>명 증가했습니다. <1>${context.date}</>"
        }
        context.addLog(log)

        val nextPop = valueFit((d.city.population + popScore).toDouble(), 0.0, d.city.populationMax.toDouble()).toInt()
        d.city = d.city.copy(population = nextPop)
        d.general = d.general.copy(
            rice = maxOf(0, d.general.rice - reqRice),
            experience = d.general.experience + exp,
            dedication = d.general.dedication + ded,
            meta = nextMeta,
        )
    }

    private fun envOf(c: ConstraintContext) = WorldEnv(
        year = (c.env["year"] as Number).toInt(),
        startYear = (c.env["startYear"] as Number).toInt(),
        develCost = (c.env["develCost"] as Number).toInt(),
    )
}

fun cheJeongchakJangnyeo(pipeline: GeneralActionPipeline, maxLevel: Int = 255) = CheJeongchakJangnyeo(pipeline, maxLevel)
