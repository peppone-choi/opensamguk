package opensamguk.logic.actions.military

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.CalcCityDistance

/**
 * che_이동 — 이동. Faithful port of `che_이동.php` run() (lines 121-172).
 *
 *   cost = [env.develcost, 0]; setVar('city', destCityID); atmos -=5 FLOOR 20 (increaseVarWithLimit
 *   '-5', min 20); gold -= develcost (floor 0); exp 50; leadership_exp += 1.
 *
 *   roaming-leader (officer_level==12 && nation.level==0): EVERY same-nation general (no!=me) moves to
 *   destCityID; each gets a per-target PLAIN log "방랑군 세력이 <G><b>{dest}</b></>{josaRo} 이동했습니다."
 *
 *   NearCity(1) over the F-MAP CalcCityDistance/nearCity module (no longer a Wave-3 gate).
 *   reqArg: arg['destCityID']. NONE draw from turn RNG.
 */
class CheIdong(
    private val pipeline: GeneralActionPipeline,
) : GeneralActionDefinition {
    override val key = "che_이동"
    override val name = "이동"
    override val category = "인사"   // PHP availableGeneralCommand groups 이동 under 인사
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destCityID" to "int")

    fun getCostGold(env: WorldEnv): Int = env.develCost

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notSameDestCity(),
        nearCity(1) { c, _ ->
            val mapName = c.env["mapName"] as? String ?: CityConstRegistry.DEFAULT_MAP_NAME
            CalcCityDistance.nearCity(c.cityId ?: 0, 1, CityConstRegistry.of(mapName))
        },
        reqGeneralGold { c, _ -> getCostGold(envOf(c)) },
        reqGeneralRice { _, _ -> 0 },
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val env = context.env
        val g0 = d.general

        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: d.destCity?.id
        ?: error("이동 requires a destCityID")
        val cityConst = CityConstRegistry.of(env.mapName)
        val destCityName = d.destCity?.let { cityConst.byId(it.id)?.name } ?: cityConst.byId(destCityId)?.name
        ?: error("unknown dest city $destCityId")
        val josaRo = JosaUtil.pick(destCityName, "로")

        context.addLog("<G><b>$destCityName</b></>$josaRo 이동했습니다. <1>${context.date}</>")

        // roaming-leader: move EVERY same-nation general (no!=me) to the dest city + per-target PLAIN log.
        if (g0.officerLevel == 12 && (d.nation?.level ?: 0) == 0) {
            for (follower in context.candidateGenerals) {
                if (follower.nationId != g0.nationId) continue
                if (follower.id == g0.id) continue
                d.cascadeGenerals.add(follower.copy(cityId = destCityId))
                // PHP $targetLogger->pushGeneralActionLog(text, PLAIN) → "<C>●</>{text}" (no month prefix).
                context.addLogTo(follower.id, "<C>●</>방랑군 세력이 <G><b>$destCityName</b></>$josaRo 이동했습니다.")
            }
        }

        val reqGold = getCostGold(env)
        var g = g0.copy(
            cityId = destCityId,                                 // setVar('city', destCityID)
            gold = maxOf(0, g0.gold - reqGold),                  // increaseVarWithLimit('gold', -reqGold, 0)
            atmos = ivwlAtmos(g0.atmos - 5.0),                   // increaseVarWithLimit('atmos', -5, 20) → floor 20
        )
        val expRes = addExperience(g, 50.0, pipeline)
        g = expRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        g = g.copy(
            meta = withMeta(g.meta, "leadership_exp" to metaDouble(g.meta, "leadership_exp") + 1.0),
            lastTurn = LastTurn(name, arg = linkedMapOf<String, Any?>("destCityID" to destCityId)),
        )
        val statRes = checkStatChange(g)
        g = statRes.general
        statRes.plainLogs.forEach { context.addPlainLog(it) }
        d.general = g
        StaticEventHandler.handleEvent(d.general, d.destGeneral, rawClassName, emptyMap(), context.args)
    }

    /** increaseVarWithLimit('atmos', -5, min 20): clamp lower-bound 20 (no upper). */
    private fun ivwlAtmos(value: Double): Double = if (value < 20.0) 20.0 else value

    private fun envOf(c: ConstraintContext) = WorldEnv(
        year = (c.env["year"] as Number).toInt(),
        startYear = (c.env["startYear"] as Number).toInt(),
        develCost = (c.env["develCost"] as Number).toInt(),
        mapName = c.env["mapName"] as? String ?: CityConstRegistry.DEFAULT_MAP_NAME,
    )
}
