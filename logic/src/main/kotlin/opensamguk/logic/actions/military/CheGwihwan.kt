package opensamguk.logic.actions.military

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notCapital
import opensamguk.logic.constraints.notWanderingNation
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry

/**
 * che_귀환 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_귀환.php`.
 *
 * Return to base (the capital, or the actor's officer_city for officer_level 2..4), AI-emitted by
 * do귀환 (GeneralAI.php:3103).
 *
 * argTest (che_귀환.php:23-26): `arg = null` → always true (no args).
 *
 * fullConditionConstraints (che_귀환.php:38-42), in PHP ORDER (first-deny-wins):
 *   [NotBeNeutral, NotWanderingNation, NotCapital(true)]
 * (NotCapital(true) = the `ignoreOfficer` branch — an officer_level 2..4 actor in the capital passes).
 *
 * run() (che_귀환.php:62-104): destCityID = officer_city (officer_level 2..4) else nation.capital;
 * move + exp 70 / ded 100 / leadership_exp +1 + the trailing unique-item lottery (a SEPARATE 'unique'
 * rng — a downstream seam, not drawn here).
 */
class CheGwihwan(private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
    override val key: String get() = "che_귀환"
    override val name: String get() = "귀환"
    override val category: String get() = "군사"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(),
        notWanderingNation(),
        notCapital(ignoreOfficer = true),
    )

    /** che_귀환.php:23-26 argTest — `arg = null` (no args). */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g0 = d.general
        val officerLevel = g0.officerLevel
        val destCityId = if (officerLevel in 2..4) metaInt(g0.meta, "officer_city")
        else (d.nation?.capitalCityId ?: return)
        val destCityName = CityConstRegistry.of(context.env.mapName).byId(destCityId)?.name ?: ""
        val josaRo = JosaUtil.pick(destCityName, "로")
        context.addLog("<G><b>$destCityName</b></>$josaRo 귀환했습니다. <1>${context.date}</>")
        var g = g0.copy(cityId = destCityId)
        val expRes = addExperience(g, 70.0, pipeline)
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
        // tryUniqueItemLottery on the SEPARATE 'unique' rng (che_귀환.php:100) — a downstream seam.
    }
}
