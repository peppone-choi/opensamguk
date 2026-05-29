package opensamguk.logic.actions.military

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.General
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
 * che_사기진작 — 사기진작. The atmos-shape twin of 훈련 (PHP che_사기진작.php run(), lines 85-130).
 *
 *   score = clamp(round(getLeadership()*100/crew * atmosDelta), 0, clamp(maxAtmosByCommand - atmos, 0))
 *   train side-effect = valueFit(intval(train * trainSideEffectByAtmosTurn), 0)
 *   increaseVar('atmos', score) (raw add); setVar('train', sideEffect)
 *   addDex(crewTypeObj, score, false); cost gold = round(crew/100) → increaseVarWithLimit('gold', -gold, 0)
 *   exp 100, ded 70; leadership_exp += 1
 *   log: "사기치가 <C>{score}</> 상승했습니다. <1>{date}</>"
 */
class CheSagiJinjak(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key = "che_사기진작"
    override val name = "사기진작"
    override val category = "군사"

    /** che_사기진작.php:69-73 — gold = round(crew/100), no rice. */
    fun getCostGold(general: General): Int = phpRound(general.crew / 100.0)

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), notWanderingNation(), occupiedCity(),
        reqGeneralCrew(),
        reqGeneralGold { c, view -> getCostGold(view.get(RequirementKey.General(c.actorId)) as General) },
        reqGeneralRice { _, _ -> 0 },
        reqGeneralAtmosMargin { _, _ -> GameConst.maxAtmosByCommand },
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g0 = d.general
        val leadership = getStatValue(g0, "leadership", pipeline, maxLevel, withInjury = true, useFloor = true)

        val raw = phpRound(leadership * 100 / g0.crew * GameConst.atmosDelta).toDouble()
        val cap = clamp(GameConst.maxAtmosByCommand - g0.atmos, 0.0)
        val score = clamp(raw, 0.0, cap)
        val scoreText = numberFormat(score.toInt())

        val sideEffect = valueFit(truncate(g0.train * GameConst.trainSideEffectByAtmosTurn), 0.0)
        val reqGold = getCostGold(g0)

        context.addLog("사기치가 <C>$scoreText</> 상승했습니다. <1>${context.date}</>")

        var g = g0.copy(
            atmos = g0.atmos + score,   // increaseVar (raw add)
            train = sideEffect,         // setVar
        )
        g = addDexForCrewType(pipeline, g, g.crewTypeId, score)
        g = g.copy(
            gold = maxOf(0, g.gold - reqGold),   // increaseVarWithLimit('gold', -reqGold, 0)
            experience = g.experience + 100.0,
            dedication = g.dedication + 70.0,
            meta = withMeta(g.meta, "leadership_exp" to metaDouble(g.meta, "leadership_exp") + 1),
            lastTurn = LastTurn(name),
        )
        d.general = g
    }
}
