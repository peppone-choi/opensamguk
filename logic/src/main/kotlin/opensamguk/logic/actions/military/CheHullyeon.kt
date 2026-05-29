package opensamguk.logic.actions.military

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.clamp
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit
import kotlin.math.truncate

/**
 * che_훈련 — 훈련. Faithful port of `che_훈련.php` run() (lines 89-131).
 *
 *   score = clamp(round(getLeadership()*100/crew * trainDelta), 0, clamp(maxTrainByCommand - train, 0))
 *   atmos side-effect = valueFit(intval(atmos * atmosSideEffectByTraining), 0)   ← truncate then lower-fit 0
 *   increaseVar('train', score) (raw add); setVar('atmos', sideEffect)
 *   addDex(crewTypeObj, score, affectTrainAtmos=false); exp 100, ded 70; leadership_exp += 1
 *   log: "훈련치가 <C>{score}</> 상승했습니다. <1>{date}</>"
 *
 * getLeadership() = getStatValue('leadership', useFloor=true) → truncated. NONE draw from turn RNG.
 */
class CheHullyeon(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key = "che_훈련"
    override val name = "훈련"
    override val category = "군사"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), notWanderingNation(), occupiedCity(),
        reqGeneralCrew(),
        reqGeneralTrainMargin { _, _ -> GameConst.maxTrainByCommand },
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g0 = d.general
        val leadership = getStatValue(g0, "leadership", pipeline, maxLevel, withInjury = true, useFloor = true)

        val raw = phpRound(leadership * 100 / g0.crew * GameConst.trainDelta).toDouble()
        val cap = clamp(GameConst.maxTrainByCommand - g0.train, 0.0)   // clamp(max - train, 0) lower-fit
        val score = clamp(raw, 0.0, cap)
        val scoreText = numberFormat(score.toInt())

        // atmos side-effect: intval(atmos * coef) then valueFit(_, 0)  (truncate toward zero, lower-bound 0)
        val sideEffect = valueFit(truncate(g0.atmos * GameConst.atmosSideEffectByTraining), 0.0)

        context.addLog("훈련치가 <C>$scoreText</> 상승했습니다. <1>${context.date}</>")

        var g = g0.copy(
            train = g0.train + score,   // increaseVar (raw add)
            atmos = sideEffect,         // setVar
        )
        g = addDexForCrewType(pipeline, g, g.crewTypeId, score)
        g = g.copy(
            experience = g.experience + 100.0,
            dedication = g.dedication + 70.0,
            meta = withMeta(g.meta, "leadership_exp" to metaDouble(g.meta, "leadership_exp") + 1),
            lastTurn = LastTurn(name),
        )
        d.general = g
    }
}
