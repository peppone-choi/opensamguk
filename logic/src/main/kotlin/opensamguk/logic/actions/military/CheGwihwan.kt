package opensamguk.logic.actions.military

import opensamguk.common.constants.CityConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notCapital
import opensamguk.logic.constraints.notWanderingNation
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline

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
class CheGwihwan(@Suppress("UNUSED_PARAMETER") private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
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
        val destCityName = CityConst.byId(destCityId)?.name ?: ""
        val josaRo = JosaUtil.pick(destCityName, "로")
        context.addLog("<G><b>$destCityName</b></>$josaRo 귀환했습니다. <1>${context.date}</>")
        d.general = g0.copy(
            cityId = destCityId,
            experience = g0.experience + 70.0,
            dedication = g0.dedication + 100.0,
            lastTurn = LastTurn(name),
            // leadership_exp +1 — the raw exp-pool increment (no per-add round; truncated at flush).
        )
        // tryUniqueItemLottery on the SEPARATE 'unique' rng (che_귀환.php:100) — a downstream seam.
    }
}
