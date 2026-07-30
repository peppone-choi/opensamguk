package opensamguk.logic.actions.develop

import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notWanderingNation
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.DEFAULT_FRONT_DEBUFF
import opensamguk.logic.domestic.FRONT_STATES
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.domestic.criticalScoreEx
import opensamguk.logic.domestic.getDomesticExpLevelBonus
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.clamp
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound
import kotlin.math.truncate

/**
 * che_물자조달 — PHP che_물자조달.php:65-158 (VERIFIED). A randomized resource-gathering command that
 * credits the NATION (gold OR rice). Stands alone (NOT a che_상업투자 subclass).
 *
 * 5-draw order (load-bearing):
 *   DRAW1 choice([['금','gold'],['쌀','rice']])  — the EXTRA LEADING draw (line 75), BEFORE nextRange.
 *   DRAW2 nextRange(0.8,1.2)                      (line 82)
 *   DRAW3 choiceUsingWeight(fail,success,normal)  (line 91) — fixed success 0.1 / fail 0.3.
 *   DRAW4 criticalScoreEx(rng, pick)              (line 96) — success/fail only.
 *   DRAW5 choiceUsingWeight(incStat: RAW stats)   (line 136) — weighted by getStat(false,false,false,false).
 *
 * Rounding/credit timing:
 *   - score = (getLeadership()+getStrength()+getIntel()) [floored sum] × expLevelBonus × DRAW2.
 *   - score = round(score) FIRST (line 99); exp = score*0.7/3, ded = score*1.0/3 (from this PRE-debuff round).
 *   - front-debuff multiplies score AFTER exp/ded (lines 106-121; capital ramp relYear<25).
 *   - log scoreText = number_format(score,0) (HALF-UP) AND nation credit = (int)score (TRUNCATE via %i)
 *     both read the POST-debuff score (identical for non-front integers; can differ for front fractions).
 *   - log hardcodes `{resName}을` (literal 을, NOT josa); the normal branch has NO `조달을 하여` prefix.
 *   - getCost [0,0]; constraints NotBeNeutral/NotWanderingNation/OccupiedCity/SuppliedCity only.
 */
class CheMuljaJodal(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
    private val frontDebuff: Double = DEFAULT_FRONT_DEBUFF,
) : GeneralActionDefinition {
    override val key: String = "che_물자조달"
    override val name: String = "물자조달"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), notWanderingNation(), occupiedCity(), suppliedCity(),
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft; val rng = context.rng; val env = context.env

        // DRAW1 — the EXTRA leading resource choice.
        val resPick = rng.choice(listOf(listOf("금", "gold"), listOf("쌀", "rice")))
        val resName = resPick[0]; val resKey = resPick[1]

        // score = floored (L + S + I) × expLevelBonus × DRAW2 nextRange.
        var score = getStatValue(d.general, "leadership", pipeline, maxLevel) +
                    getStatValue(d.general, "strength", pipeline, maxLevel) +
                    getStatValue(d.general, "intelligence", pipeline, maxLevel)
        score *= getDomesticExpLevelBonus(metaInt(d.general.meta, "explevel"))
        score *= rng.nextRange(0.8, 1.2)                                          // DRAW2

        var successRatio = 0.1
        var failRatio = 0.3
        successRatio = pipeline.onCalcDomestic(d.general, "조달", "success", successRatio)
        failRatio = pipeline.onCalcDomestic(d.general, "조달", "fail", failRatio)
        val normalRatio = 1.0 - failRatio - successRatio                          // NO clamp (PHP che_물자조달.php:89)

        val pick = rng.choiceUsingWeight(linkedMapOf(                             // DRAW3
            "fail" to failRatio, "success" to successRatio, "normal" to normalRatio))
        score *= criticalScoreEx(rng, pick)                                       // DRAW4
        score = pipeline.onCalcDomestic(d.general, "조달", "score", score)

        val roundedScore = phpRound(score)                                        // line 99 — round FIRST

        val exp = roundedScore * 0.7 / 3.0                                        // PRE-debuff rounded score
        val ded = roundedScore * 1.0 / 3.0

        // front-debuff (AFTER exp/ded). scoreFloat carries the post-debuff value.
        var scoreFloat = roundedScore.toDouble()
        if (d.city.frontState in FRONT_STATES) {
            var debuff = frontDebuff
            if (d.nation?.capitalCityId == d.city.id && env.relYear < 25) {
                val scale = clamp((env.relYear - 5).toDouble(), 0.0, 20.0) * 0.05
                debuff = scale * frontDebuff + (1 - scale)
            }
            scoreFloat *= debuff
        }

        // log scoreText = number_format(score, 0) (HALF-UP); hardcoded 을; normal branch lacks the 조달을 하여 prefix.
        val scoreText = numberFormat(phpRound(scoreFloat))
        val log = when (pick) {
            "fail"    -> "조달을 <span class='ev_failed'>실패</span>하여 ${resName}을 <C>$scoreText</> 조달했습니다. <1>${context.date}</>"
            "success" -> "조달을 <S>성공</>하여 ${resName}을 <C>$scoreText</> 조달했습니다. <1>${context.date}</>"
            else      -> "${resName}을 <C>$scoreText</> 조달했습니다. <1>${context.date}</>"
        }
        context.addLog(log)

        // DRAW5 — incStat weighted by RAW stats (all flags false).
        val incStat = rng.choiceUsingWeight(linkedMapOf(
            "leadership_exp" to rawStat(d, "leadership"),
            "strength_exp" to rawStat(d, "strength"),
            "intel_exp" to rawStat(d, "intelligence")))

        var nextGeneral = d.general
        val experienceResult = addExperience(nextGeneral, exp, pipeline)
        nextGeneral = experienceResult.general
        experienceResult.plainLog?.let(context::addPlainLog)

        val dedicationResult = addDedication(nextGeneral, ded, pipeline)
        nextGeneral = dedicationResult.general
        dedicationResult.plainLog?.let(context::addPlainLog)

        d.general = nextGeneral.copy(
            meta = withMeta(nextGeneral.meta, incStat to metaInt(nextGeneral.meta, incStat) + 1),
        )

        // nation credit = (int)score (TRUNCATE via the meekrodb %i cast) of the POST-debuff score.
        val credit = truncate(scoreFloat).toInt()
        d.nation = when (resKey) {
            "gold" -> d.nation?.copy(gold = (d.nation?.gold ?: 0) + credit)
            else   -> d.nation?.copy(rice = (d.nation?.rice ?: 0) + credit)
        }
        d.general = d.general.copy(lastTurn = LastTurn(name))
        val statChangeResult = checkStatChange(d.general)
        d.general = statChangeResult.general
        statChangeResult.plainLogs.forEach(context::addPlainLog)
        StaticEventHandler.handleEvent(d.general, d.destGeneral, rawClassName, emptyMap(), context.args)
    }

    private fun rawStat(d: GeneralActionDraft, statName: String): Double =
        getStatValue(d.general, statName, pipeline, maxLevel,
            withInjury = false, withIActionObj = false, withStatAdjust = false, useFloor = false)
}

fun cheMuljaJodal(pipeline: GeneralActionPipeline, maxLevel: Int = 255) = CheMuljaJodal(pipeline, maxLevel)
