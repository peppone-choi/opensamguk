package opensamguk.logic.actions.military

import opensamguk.common.constants.CityConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notWanderingNation
import opensamguk.logic.domain.LastTurn

/**
 * che_접경귀환 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_접경귀환.php`.
 *
 * 접근하는 적/중립 도시 중 가장 가까운 도시로 귀환한다.
 * No success/fail branch. Single draw: choice(nearestCityList) ×1.
 *
 * PHP run():
 *   $nearestCityList = ...; // nearest enemy/neutral cities
 *   $destCityID = $rng->choice($nearestCityList);
 *   $general->setVar('city', $destCityID);
 *   log("<G><b>{$destCityName}</b></>로 접경귀환했습니다. <1>$date</>");
 */
class CheJeopgyeongGwihwan : GeneralActionDefinition {
    override val key = "che_접경귀환"
    override val name = "접경귀환"
    override val category = "군사"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(),
        notWanderingNation(),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g0 = d.general
        val rng = context.rng
        val date = context.date

        // nearest enemy/neutral city list — preloaded by engine adapter via candidateCityIds
        val nearestCityList = context.candidateCityIds
        if (nearestCityList.isEmpty()) return

        val destCityId = rng.choice(nearestCityList)                           // DRAW1
        val destCityName = CityConst.byId(destCityId)?.name ?: ""
        val josaRo = JosaUtil.pick(destCityName, "로")

        context.addLog("<G><b>$destCityName</b></>$josaRo 접경귀환했습니다. <1>$date</>")
        d.general = g0.copy(
            cityId = destCityId,
            lastTurn = LastTurn(name),
        )
    }
}
