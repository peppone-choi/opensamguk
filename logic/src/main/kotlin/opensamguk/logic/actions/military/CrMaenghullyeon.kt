package opensamguk.logic.actions.military

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound

/**
 * cr_맹훈련 — 맹훈련. Faithful port of `cr_맹훈련.php` run() (lines 79-114).
 *
 *   score = round(getLeadership()*100/crew * trainDelta * 2/3)   ← NO score clamp (unlike 훈련)
 *   increaseVarWithLimit('train', score, 0, maxTrainByCommand)   ← clamp(train+score, 0, 100)
 *   increaseVarWithLimit('atmos', score, 0, maxAtmosByCommand)   ← clamp(atmos+score, 0, 100)
 *   addDex(crewTypeObj, score*2, false); exp 150, ded 100; leadership_exp += 1
 *   log: "훈련, 사기치가 <C>{score}</> 상승했습니다. <1>{date}</>"
 *   getCost() = [0, 500] is title/constraint-only — run() does NOT decrement rice.
 *
 * rawClassName = key = "cr_맹훈련" (the cr_ prefix is preserved); NONE draw from turn RNG.
 */
class CrMaenghullyeon(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key = "cr_맹훈련"
    override val name = "맹훈련"
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

        val score = phpRound(leadership * 100 / g0.crew * GameConst.trainDelta * 2.0 / 3.0).toDouble()
        val scoreText = numberFormat(score.toInt())

        context.addLog("훈련, 사기치가 <C>$scoreText</> 상승했습니다. <1>${context.date}</>")

        // increaseVarWithLimit: clamp(current+delta, min, max) — sequential min then max (PHP LazyVarUpdater:80-91).
        val nextTrain = ivwl(g0.train + score, 0.0, GameConst.maxTrainByCommand.toDouble())
        val nextAtmos = ivwl(g0.atmos + score, 0.0, GameConst.maxAtmosByCommand.toDouble())

        var g = g0.copy(train = nextTrain, atmos = nextAtmos)
        g = addDexForCrewType(pipeline, g, g.crewTypeId, score * 2)   // addDex score*2
        val expRes = addExperience(g, 150.0, pipeline)
        g = expRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        val dedRes = addDedication(g, 100.0, pipeline)
        g = dedRes.general
        dedRes.plainLog?.let { context.addPlainLog(it) }
        g = g.copy(
            meta = withMeta(g.meta, "leadership_exp" to metaDouble(g.meta, "leadership_exp") + 1.0),
            lastTurn = LastTurn(name),
        )
        val statRes = checkStatChange(g)
        g = statRes.general
        statRes.plainLogs.forEach { context.addPlainLog(it) }
        d.general = g
        StaticEventHandler.handleEvent(d.general, d.destGeneral, rawClassName, emptyMap(), context.args)
    }

    /** PHP increaseVarWithLimit clamp: lower-bound min first, then upper-bound max (sequential, no inversion guard). */
    private fun ivwl(value: Double, min: Double, max: Double): Double {
        var v = value
        if (v < min) v = min
        if (v > max) v = max
        return v
    }
}
