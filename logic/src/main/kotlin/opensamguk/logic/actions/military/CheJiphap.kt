package opensamguk.logic.actions.military

import opensamguk.common.constants.CityConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_집합 — 집합. Faithful port of `che_집합.php` run() (lines 65-112).
 *
 *   Gathers the leader's troop members to the leader's CURRENT city: every same-nation general with
 *   city != cityID AND troop == troopID(=leader.id) AND no != me moves to the leader's city; each gets
 *   a per-target PLAIN log "{troopName} 부대원들은 <G><b>{city}</b></>{josaRo} 집합되었습니다."
 *   leader: exp 70, ded 100, leadership_exp += 1.
 *   log: "<G><b>{city}</b></>에서 집합을 실시했습니다. <1>{date}</>"
 *
 * The candidate members + troopName are preloaded into the resolve context (the DB query result).
 * NONE draw from turn RNG.
 */
class CheJiphap(
    private val pipeline: GeneralActionPipeline,
) : GeneralActionDefinition {
    override val key = "che_집합"
    override val name = "집합"
    override val category = "군사"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), occupiedCity(), suppliedCity(),
        mustBeTroopLeader(),
        reqTroopMembers { ctx, view ->
            val actor = view.get(RequirementKey.General(ctx.actorId)) as? General
            val generals = view.get(RequirementKey.GeneralList) as? Collection<*>
            actor != null && generals.orEmpty().filterIsInstance<General>()
                .any { it.id != actor.id && it.troop == actor.id }
        },
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g0 = d.general

        val cityId = d.city.id
        val cityName = CityConst.byId(cityId)?.name ?: error("unknown city $cityId")
        val josaRo = JosaUtil.pick(cityName, "로")
        val troopId = g0.id   // PHP troopID = $general->getID() (the leader is the troop id)

        context.addLog("<G><b>$cityName</b></>에서 집합을 실시했습니다. <1>${context.date}</>")

        // PHP: general WHERE nation=me AND city!=cityID AND troop=troopID AND no!=me → city=cityID.
        for (member in context.candidateGenerals) {
            if (member.nationId != g0.nationId) continue
            if (member.cityId == cityId) continue
            if (member.troop != troopId) continue
            if (member.id == g0.id) continue
            d.cascadeGenerals.add(member.copy(cityId = cityId))
            context.addLogTo(member.id,
                "<C>●</>${context.troopName} 부대원들은 <G><b>$cityName</b></>$josaRo 집합되었습니다.")
        }

        var g = g0
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
    }
}
