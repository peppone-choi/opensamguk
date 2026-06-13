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
    private val cityKey: String,        // "comm" | "agri" | "secu" | "def" | "wall"  (NO "tech" — tech is a NATION stat)
    private val statKey: String,        // "intel" | "strength"
    private val actionKey: String,      // "상업" | "농업" | "성벽" | "수비" | "치안"
    override val name: String,          // "상업 투자" | "성벽 보수" … (WITH space — PHP)
    private val maxLevel: Int = 255,
    private val frontDebuff: Double = DEFAULT_FRONT_DEBUFF,
) : GeneralActionDefinition {
    override val key: String get() = "che_${name.replace(" ", "")}"   // che_상업투자 / che_성벽보수

    /** statKey → the getStatValue stat name (PHP che_상업투자.php:108-119). */
    private val statName: String = when (statKey) {
        "intel" -> "intelligence"
        "strength" -> "strength"
        "leadership" -> "leadership"
        else -> error("unknown statKey $statKey")
    }

    fun getCost(general: General, env: WorldEnv): Int =
        phpRound(pipeline.onCalcDomestic(general, actionKey, "cost", env.develCost.toDouble()))

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), notWanderingNation(), occupiedCity(), suppliedCity(),
        reqGeneralGold { c, view -> getCost(view.get(RequirementKey.General(c.actorId)) as General, envOf(c)) },
        reqGeneralRice { _, _ -> 0 },
        // RemainCityCapacity (PHP RemainCityCapacity.php — generic over city[$key]/city[$key.'_max'];
        // built inline here to span the secu/def/wall columns the P1 comm/agri preset switch omits).
        remainCityCapacity(),
    )

    /** Generic RemainCityCapacity over the 5 develop columns (comm/agri/secu/def/wall). */
    private fun remainCityCapacity() = object : Constraint {
        override val name = "RemainCityCapacity"
        override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
        override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
            val c = view.get(RequirementKey.City(ctx.cityId ?: (view.get(RequirementKey.General(ctx.actorId)) as? General)?.cityId ?: 0)) as? City
                ?: return ConstraintResult.Unknown(requires(ctx))
            if (cityCur(c) < cityMax(c)) return ConstraintResult.Allow
            val josaUn = JosaUtil.pick(this@CommerceInvestment.name, "은")
            return ConstraintResult.Deny("${this@CommerceInvestment.name}$josaUn 충분합니다.")
        }
    }

    private fun cityCur(c: City): Int = when (cityKey) {
        "comm" -> c.commerce; "agri" -> c.agriculture
        "secu" -> c.security; "def" -> c.defense; "wall" -> c.wall
        else -> error("unknown cityKey $cityKey")
    }
    private fun cityMax(c: City): Int = when (cityKey) {
        "comm" -> c.commerceMax; "agri" -> c.agricultureMax
        "secu" -> c.securityMax; "def" -> c.defenseMax; "wall" -> c.wallMax
        else -> error("unknown cityKey $cityKey")
    }

    private fun calcBaseScore(d: GeneralActionDraft, rng: opensamguk.common.rng.RandUtil, env: WorldEnv): Double {
        val trust = valueFit(d.city.trust, DEFAULT_TRUST.toDouble())  // lower-bound only; trust is Double (PHP FLOAT)
        // DIVERGENCE (flag-gated): intel-driven 개발(농지개간/상업투자)만 politics로 스왑. flag OFF 또는
        // strength-driven(수비/치안/성벽)이면 baseline과 byte-identical.
        val resolvedStatName = if (env.fiveStatDomestic && statName == "intelligence") "politics" else statName
        var score = getStatValue(d.general, resolvedStatName, pipeline, maxLevel, withInjury = true, useFloor = false)
        score *= trust / 100.0   // PHP che_상업투자.php:121 — fractional, trust is Double
        score *= getDomesticExpLevelBonus(metaInt(d.general.meta, "explevel"))
        score *= rng.nextRange(0.8, 1.2)                                          // DRAW 1
        return pipeline.onCalcDomestic(d.general, actionKey, "score", score)
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft; val rng = context.rng; val env = context.env
        val reqGold = getCost(d.general, env)
        val trust = valueFit(d.city.trust, DEFAULT_TRUST.toDouble())   // Double (PHP FLOAT)
        var score = valueFit(calcBaseScore(d, rng, env), 1.0)

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

        // exp/ded gains fold THROUGH the onCalcStat stack (PHP General::addExperience / addDedication,
        // General.php:448-495 — affectTrigger default TRUE: $exp = $this->onCalcStat($this,'experience',$exp)
        // BEFORE increaseVar). A module-free general folds identity (so the P1/develop module-free goldens
        // are unaffected); a personality like che_왕좌 multiplies experience ×1.1. The level-change PLAIN
        // logs (레벨업/승급) live in StatChange.addExperience/addDedication; the develop goldens are pinned
        // no-level-cross so they are not emitted here — the GATE-TRAIT non-identity golden proves this fold.
        val exp = pipeline.onCalcStat(d.general, "experience", roundedScore * 0.7)
        val ded = pipeline.onCalcStat(d.general, "dedication", roundedScore * 1.0)

        // max_domestic_critical (success: aux += score/2; else reset to 0) — meta/aux ONLY (no inheritance write; OQ7/P6 seam)
        // PHP increaseVar(static::$statKey.'_exp', 1) — the exp key follows statKey (intel_exp / strength_exp).
        val expKey = "${statKey}_exp"
        val nextMeta = if (pick == "success") {
            val nextAux = updateMaxDomesticCritical(metaDouble(d.general.meta, "max_domestic_critical"), roundedScore)
            withMeta(d.general.meta, expKey to metaInt(d.general.meta, expKey) + 1,
                     "max_domestic_critical" to nextAux)
        } else {
            withMeta(d.general.meta, expKey to metaInt(d.general.meta, expKey) + 1,
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

        // mutations (immutable draft replacement) — generic over the 5 develop columns
        val nextCityVal = valueFit(cityCur(d.city) + cityScore, 0.0, cityMax(d.city).toDouble()).toInt()
        d.city = when (cityKey) {
            "comm" -> d.city.copy(commerce = nextCityVal)
            "agri" -> d.city.copy(agriculture = nextCityVal)
            "secu" -> d.city.copy(security = nextCityVal)
            "def"  -> d.city.copy(defense = nextCityVal)
            "wall" -> d.city.copy(wall = nextCityVal)
            else -> error("unknown cityKey $cityKey")
        }
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
