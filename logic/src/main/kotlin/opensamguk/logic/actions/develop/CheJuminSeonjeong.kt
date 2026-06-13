package opensamguk.logic.actions.develop

import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notWanderingNation
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.remainCityTrust
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.General
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.criticalRatioDomestic
import opensamguk.logic.domestic.criticalScoreEx
import opensamguk.logic.domestic.getDomesticExpLevelBonus
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.clamp
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit

/**
 * che_주민선정 — PHP che_주민선정.php. A leadership-stat develop command that is NOT a che_상업투자
 * subclass. The decisive divergences (research Unit 1 gap):
 *  - statKey='leadership', cityKey='trust' → mutates city.trust (clamp [0, 100]).
 *  - calcBaseScore: leadership × expLevelBonus × DRAW1 × valueFit(,1) — NO trust factor.
 *  - getCost: develCost*2 as RICE (gold=0); RemainCityTrust('주민 선정') (NOT RemainCityCapacity).
 *  - run() does NOT round the score → exp=score*0.7, ded=score*1.0, max_domestic_critical += score/2
 *    are ALL FRACTIONAL (the UN-rounded score; PHP passes the un-rounded score to updateMaxDomesticCritical).
 *  - score /= 10 AFTER exp/ded + maxCrit; scoreText = number_format(score, 1) — ONE decimal.
 *  - log: `주민 선정{을} … <C>{scoreText}</> 상승했습니다.`
 *  - increaseVar('leadership_exp', 1). NO front-debuff.
 */
class CheJuminSeonjeong(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key: String = "che_주민선정"
    override val name: String = "주민 선정"
    private val actionKey = "민심"
    private val statKey = "leadership"

    /** PHP getCost: reqGold=0, reqRice=round(onCalcDomestic('민심','cost', develCost*2)). */
    fun getReqRice(general: General, env: WorldEnv): Int =
        phpRound(pipeline.onCalcDomestic(general, actionKey, "cost", (env.develCost * 2).toDouble()))

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), notWanderingNation(), occupiedCity(), suppliedCity(),
        reqGeneralGold { _, _ -> 0 },
        reqGeneralRice { c, view -> getReqRice(view.get(RequirementKey.General(c.actorId)) as General, envOf(c)) },
        remainCityTrust(name),
    )

    /** PHP che_주민선정.php:102-118 — calcBaseScore ends with its OWN valueFit(score, 1). */
    private fun calcBaseScore(d: GeneralActionDraft, rng: RandUtil, env: WorldEnv): Double {
        // DIVERGENCE (flag-gated): leadership-driven 민심을 charm으로 스왑. flag OFF면 baseline과 byte-identical.
        val resolvedStatName = if (env.fiveStatLogic) "charm" else "leadership"
        var score = getStatValue(d.general, resolvedStatName, pipeline, maxLevel, withInjury = true, useFloor = false)
        score *= getDomesticExpLevelBonus(metaInt(d.general.meta, "explevel"))
        score *= rng.nextRange(0.8, 1.2)                                          // DRAW 1
        score = pipeline.onCalcDomestic(d.general, actionKey, "score", score)
        return valueFit(score, 1.0)
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft; val rng = context.rng; val env = context.env
        val reqRice = getReqRice(d.general, env)
        var score = valueFit(calcBaseScore(d, rng, env), 1.0)                     // run() valueFit on top of calcBaseScore's

        val ratio = criticalRatioDomestic(d.general, statKey, pipeline, maxLevel)
        var successRatio = ratio.success; var failRatio = ratio.fail
        // NOTE: 주민선정 has NO `if(trust<80)` adjustment (che_주민선정.php:131-133).
        successRatio = pipeline.onCalcDomestic(d.general, actionKey, "success", successRatio)
        failRatio = pipeline.onCalcDomestic(d.general, actionKey, "fail", failRatio)
        successRatio = clamp(successRatio, 0.0, 1.0)
        failRatio = clamp(failRatio, 0.0, 1.0 - successRatio)
        val normalRatio = 1.0 - failRatio - successRatio

        val pick = rng.choiceUsingWeight(linkedMapOf(                              // DRAW 2
            "fail" to failRatio, "success" to successRatio, "normal" to normalRatio))
        score *= criticalScoreEx(rng, pick)                                       // DRAW 3 — NO round

        val exp = score * 0.7   // FRACTIONAL — from the un-rounded score
        val ded = score * 1.0

        val expKey = "${statKey}_exp"
        val nextMeta = if (pick == "success") {
            // PHP updateMaxDomesticCritical($general, $score) with the UN-rounded fractional score → +score/2.
            val nextAux = metaDouble(d.general.meta, "max_domestic_critical") + score / 2.0
            withMeta(d.general.meta, expKey to metaInt(d.general.meta, expKey) + 1,
                     "max_domestic_critical" to nextAux)
        } else {
            withMeta(d.general.meta, expKey to metaInt(d.general.meta, expKey) + 1,
                     "max_domestic_critical" to 0)
        }

        // score /= 10 (AFTER exp/ded + maxCrit).
        val trustScore = score / 10.0

        val scoreText = "%,.1f".format(trustScore)   // PHP number_format($score, 1) — one decimal, comma grouping
        val josaUl = JosaUtil.pick(name, "을")
        val log = when (pick) {
            "fail"    -> "$name$josaUl <span class='ev_failed'>실패</span>하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
            "success" -> "$name$josaUl <S>성공</>하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
            else      -> "$name$josaUl 하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
        }
        context.addLog(log)

        // city.trust = valueFit(trust + score, 0, 100) — fractional FLOAT trust.
        val nextTrust = valueFit(d.city.trust + trustScore, 0.0, 100.0)
        d.city = d.city.copy(trust = nextTrust)
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

fun cheJuminSeonjeong(pipeline: GeneralActionPipeline, maxLevel: Int = 255) = CheJuminSeonjeong(pipeline, maxLevel)
