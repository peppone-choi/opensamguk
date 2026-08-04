package opensamguk.logic.actions.military

import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_소집해제 — 소집해제. Faithful port of `che_소집해제.php` run() (lines 56-88).
 *
 *   crewUp = onCalcDomestic('징집인구', 'score', crew)   (identity under no modules → raw crew)
 *   city.pop += crewUp; general.crew = 0
 *   exp 70, ded 100; NO leadership_exp, NO addDex, NO unique lottery
 *   log: "병사들을 <R>소집해제</>하였습니다. <1>{date}</>"
 *
 * The ONLY constraint is ReqGeneralCrew (che_소집해제.php:32-34) — no neutral/occupied/supplied checks.
 */
class CheSojipHaeje(
    private val pipeline: GeneralActionPipeline,
) : GeneralActionDefinition {
    override val key = "che_소집해제"
    override val name = "소집해제"
    override val category = "군사"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        reqGeneralCrew(),
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g0 = d.general

        context.addLog("병사들을 <R>소집해제</>하였습니다. <1>${context.date}</>")

        val crewUp = pipeline.onCalcDomestic(g0, "징집인구", "score", g0.crew.toDouble()).toInt()
        d.city = d.city.copy(population = d.city.population + crewUp)

        var g = g0.copy(
            crew = 0,
        )
        val expRes = addExperience(g, 70.0, pipeline)
        g = expRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        val dedRes = addDedication(g, 100.0, pipeline)
        g = dedRes.general
        dedRes.plainLog?.let { context.addPlainLog(it) }
        g = g.copy(
            lastTurn = LastTurn(name),
            // NO leadership_exp, NO addDex (per PHP run()).
        )
        val statRes = checkStatChange(g)
        g = statRes.general
        statRes.plainLogs.forEach { context.addPlainLog(it) }
        d.general = g
        StaticEventHandler.handleEvent(d.general, d.destGeneral, rawClassName, emptyMap(), context.args)
    }
}
