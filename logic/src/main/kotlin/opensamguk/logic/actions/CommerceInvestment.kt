package opensamguk.logic.actions

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.constraints.*
import opensamguk.logic.domestic.*
import opensamguk.logic.domain.*
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.*

open class CommerceInvestment(
    private val pipeline: GeneralActionPipeline,
    private val cityKey: String,        // "comm" | "agri"
    private val statKey: String,        // "intel"
    private val actionKey: String,      // "상업" | "농업"
    override val name: String,          // "상업 투자" | "농지 개간" (WITH space — PHP)
    private val maxLevel: Int = 255,
    private val frontDebuff: Double = DEFAULT_FRONT_DEBUFF,
) : GeneralActionDefinition {
    override val key: String get() = "che_${name.replace(" ", "")}"   // che_상업투자 / che_농지개간

    fun getCost(general: General, env: WorldEnv): Int =
        phpRound(pipeline.onCalcDomestic(general, actionKey, "cost", env.develCost.toDouble()))

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), notWanderingNation(), occupiedCity(), suppliedCity(),
        reqGeneralGold { c, view -> getCost(view.get(RequirementKey.General(c.actorId)) as General, envOf(c)) },
        reqGeneralRice { _, _ -> 0 },
        remainCityCapacity(cityKey, name),
    )

    private fun calcBaseScore(d: GeneralActionDraft, rng: opensamguk.common.rng.RandUtil): Double {
        val trust = valueFit(d.city.trust, DEFAULT_TRUST.toDouble())  // lower-bound only; trust is Double (PHP FLOAT)
        var score = getStatValue(d.general, "intelligence", pipeline, maxLevel, withInjury = true, useFloor = false)
        score *= trust / 100.0   // PHP che_상업투자.php:124 — fractional, trust is Double
        score *= getDomesticExpLevelBonus(metaInt(d.general.meta, "explevel"))
        score *= rng.nextRange(0.8, 1.2)                                          // DRAW 1
        return pipeline.onCalcDomestic(d.general, actionKey, "score", score)
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft; val rng = context.rng; val env = context.env
        val reqGold = getCost(d.general, env)
        val trust = valueFit(d.city.trust, DEFAULT_TRUST.toDouble())   // Double (PHP FLOAT)
        var score = valueFit(calcBaseScore(d, rng), 1.0)

        val ratio = criticalRatioDomestic(d.general, statKey, pipeline, maxLevel)
        var successRatio = ratio.success; var failRatio = ratio.fail
        if (trust < 80) successRatio *= trust / 80.0   // PHP che_상업투자.php:144 — fractional, trust is Double
        successRatio = pipeline.onCalcDomestic(d.general, actionKey, "success", successRatio)
        failRatio = pipeline.onCalcDomestic(d.general, actionKey, "fail", failRatio)
        successRatio = clamp(successRatio, 0.0, 1.0)
        failRatio = clamp(failRatio, 0.0, 1.0 - successRatio)
        val normalRatio = 1.0 - failRatio - successRatio

        val pick = rng.choiceUsingWeight(linkedMapOf(                              // DRAW 2 (key order fail,success,normal)
            "fail" to failRatio, "success" to successRatio, "normal" to normalRatio))

        score *= criticalScoreEx(rng, pick)                                       // DRAW 3 (only success/fail)
        val roundedScore = phpRound(score)                                        // POST-crit, PRE-front-debuff

        val exp = roundedScore * 0.7
        val ded = roundedScore * 1.0

        // max_domestic_critical (success: aux += score/2; else reset to 0) — meta/aux ONLY (no inheritance write; OQ7/P6 seam)
        val nextMeta = if (pick == "success") {
            val nextAux = updateMaxDomesticCritical(metaDouble(d.general.meta, "max_domestic_critical"), roundedScore)
            withMeta(d.general.meta, "intel_exp" to metaInt(d.general.meta, "intel_exp") + 1,
                     "max_domestic_critical" to nextAux)
        } else {
            withMeta(d.general.meta, "intel_exp" to metaInt(d.general.meta, "intel_exp") + 1,
                     "max_domestic_critical" to 0)
        }

        // LOG (scoreText from PRE-front-debuff roundedScore; name WITH space; <1>date</> suffix — PHP)
        val scoreText = numberFormat(roundedScore)
        val josaUl = JosaUtil.pick(name, "을")
        val log = when (pick) {
            "fail"    -> "$name$josaUl <span class='ev_failed'>실패</span>하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
            "success" -> "$name$josaUl <S>성공</>하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
            else      -> "$name$josaUl 하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
        }
        context.addLog(log)

        // front-debuff (applied to score AFTER exp/ded + log)
        var cityScore = roundedScore.toDouble()
        if (d.city.frontState in FRONT_STATES) {
            var debuff = frontDebuff
            if (d.nation?.capitalCityId == d.city.id && env.relYear < 25) {
                val scale = clamp((env.relYear - 5).toDouble(), 0.0, 20.0) * 0.05
                debuff = scale * frontDebuff + (1 - scale)
            }
            cityScore *= debuff
        }

        // mutations (immutable draft replacement)
        val curCity = if (cityKey == "comm") d.city.commerce else d.city.agriculture
        val maxCity = if (cityKey == "comm") d.city.commerceMax else d.city.agricultureMax
        val nextCityVal = valueFit(curCity + cityScore, 0.0, maxCity.toDouble()).toInt()
        d.city = if (cityKey == "comm") d.city.copy(commerce = nextCityVal) else d.city.copy(agriculture = nextCityVal)
        d.general = d.general.copy(
            gold = maxOf(0, d.general.gold - reqGold),
            // PHP increaseVar (LazyVarUpdater.php:68) = raw + delta with NO per-add rounding.
            // experience/dedication are Double in-memory; truncate-toward-zero → Int happens ONLY in the D1 row mapper at flush.
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
