package opensamguk.logic.actions.develop

import opensamguk.common.constants.GameConst
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
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.General
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.DEFAULT_TRUST
import opensamguk.logic.domestic.criticalRatioDomestic
import opensamguk.logic.domestic.criticalScoreEx
import opensamguk.logic.domestic.getDomesticExpLevelBonus
import opensamguk.logic.domestic.techLimit
import opensamguk.logic.domestic.updateMaxDomesticCritical
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.clamp
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit

/**
 * che_기술연구 — PHP che_기술연구.php:25-143 (`extends che_상업투자`, statKey='intel', actionKey='기술',
 * actionName='기술 연구'). It shares che_상업투자's calcBaseScore + critical pipeline but DIVERGES in:
 *   - init() drops RemainCityCapacity (6 constraints, no city-capacity gate),
 *   - run() has NO front-debuff,
 *   - exp/ded are computed from the rounded score BEFORE the TechLimit /4,
 *   - if TechLimit(startYear, year, nation.tech) then score /= 4 (AFTER exp/ded + the log),
 *   - genCount = valueFit(nation.gennum, initialNationGenLimit=10),
 *   - the write target is nation.tech += score/genCount (a NATION float write — NOT a city column),
 *   - the log scoreText is the PRE-/4 rounded score (PHP emits the log at line 104, before /4 at 117).
 *
 * statKey='intel' (intelligence-driven), name WITH a space.
 */
class CheGisulYeongu(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key: String = "che_기술연구"
    override val name: String = "기술 연구"
    private val actionKey = "기술"
    private val statKey = "intel"

    fun getCost(general: General, env: WorldEnv): Int =
        phpRound(pipeline.onCalcDomestic(general, actionKey, "cost", env.develCost.toDouble()))

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), notWanderingNation(), occupiedCity(), suppliedCity(),
        reqGeneralGold { c, view -> getCost(view.get(RequirementKey.General(c.actorId)) as General, envOf(c)) },
        reqGeneralRice { _, _ -> 0 },
        // NOTE: NO RemainCityCapacity (che_기술연구.php:45-52).
    )

    private fun calcBaseScore(d: GeneralActionDraft, rng: RandUtil, env: WorldEnv): Double {
        val trust = valueFit(d.city.trust, DEFAULT_TRUST.toDouble())
        // DIVERGENCE (flag-gated): intel-driven 기술연구를 politics로 스왑. flag OFF면 baseline과 byte-identical.
        val resolvedStatName = if (env.fiveStatLogic) "politics" else "intelligence"
        var score = getStatValue(d.general, resolvedStatName, pipeline, maxLevel, withInjury = true, useFloor = false)
        score *= trust / 100.0
        score *= getDomesticExpLevelBonus(metaInt(d.general.meta, "explevel"))
        score *= rng.nextRange(0.8, 1.2)                                          // DRAW 1
        return pipeline.onCalcDomestic(d.general, actionKey, "score", score)
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft; val rng = context.rng; val env = context.env
        val reqGold = getCost(d.general, env)
        val trust = valueFit(d.city.trust, DEFAULT_TRUST.toDouble())
        var score = valueFit(calcBaseScore(d, rng, env), 1.0)

        val ratio = criticalRatioDomestic(d.general, statKey, pipeline, maxLevel)
        var successRatio = ratio.success; var failRatio = ratio.fail
        if (trust < 80) successRatio *= trust / 80.0
        successRatio = pipeline.onCalcDomestic(d.general, actionKey, "success", successRatio)
        failRatio = pipeline.onCalcDomestic(d.general, actionKey, "fail", failRatio)
        successRatio = clamp(successRatio, 0.0, 1.0)
        failRatio = clamp(failRatio, 0.0, 1.0 - successRatio)
        val normalRatio = 1.0 - failRatio - successRatio

        val pick = rng.choiceUsingWeight(linkedMapOf(                              // DRAW 2
            "fail" to failRatio, "success" to successRatio, "normal" to normalRatio))

        var scoreD = score * criticalScoreEx(rng, pick)                           // DRAW 3
        val roundedScore = phpRound(scoreD)

        val exp = roundedScore * 0.7        // PHP lines 94-95: from the rounded score BEFORE the /4
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

        // LOG — scoreText is the PRE-/4 rounded score (PHP line 104, before TechLimit /4 at line 117).
        val scoreText = numberFormat(roundedScore)
        val josaUl = JosaUtil.pick(name, "을")
        val log = when (pick) {
            "fail"    -> "$name$josaUl <span class='ev_failed'>실패</span>하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
            "success" -> "$name$josaUl <S>성공</>하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
            else      -> "$name$josaUl 하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
        }
        context.addLog(log)

        // TechLimit /4 (no front-debuff). score is the rounded int as a Double from here.
        var techScore = roundedScore.toDouble()
        if (techLimit(env.startYear, env.year, d.nation?.tech ?: 0.0)) {
            techScore /= 4.0
        }
        val genCount = valueFit((d.nation?.gennum ?: 0).toDouble(), GameConst.initialNationGenLimit.toDouble())
        val nextTech = (d.nation?.tech ?: 0.0) + techScore / genCount

        d.nation = d.nation?.copy(tech = nextTech)
        d.general = d.general.copy(
            gold = maxOf(0, d.general.gold - reqGold),
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

fun cheGisulYeongu(pipeline: GeneralActionPipeline, maxLevel: Int = 255) = CheGisulYeongu(pipeline, maxLevel)
