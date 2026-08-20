package opensamguk.logic.actions.nation

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.bumpCapset
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.occupiedDestCity
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.constraints.suppliedDestCity
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domain.Nation
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry

/**
 * che_천도 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_천도.php`.
 *
 * Relocate the capital. The distance = `CalcCityDistance(capital, dest, ownedCities) ?? 50` (engine-
 * resolved, threaded via [GeneralActionResolveContext.cityDistance]). cost = develcost*5 * 2^distance,
 * preReqTurn = distance*2 (both VARIABLE). run() does NOT debit gold/rice — the cost is a PRECONDITION
 * only (ReqNationGold/Rice in buildConstraints). On run(): nation.capital → dest, capset+1,
 * refreshNationStaticInfo (engine invalidation), exp/ded += 5*(preReqTurn+1), the inline 천도 logs.
 *
 * The `last천도Trial` KV (che_천도.php:140) fires on EVERY availability poll (inside addTermStack), a
 * per-poll engine side-effect — NOT a run()-only mutation.
 */
fun cheCheondo(pipeline: GeneralActionPipeline): CheCheondo = CheCheondo(pipeline)

class CheCheondo(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_천도"
    override val name: String get() = "천도"
    override val argsSchema: Map<String, Any?> get() = mapOf("destCityID" to "int")

    /** Resolved at runtime from the threaded distance; the abstract [getPreReqTurn] uses the same shape. */
    override fun getPreReqTurn(): Int = 0  // distance-driven at resolve; base preReq computed via helpers

    /** che_천도.php:99-105 — develcost*5 * 2^distance. */
    fun getCost(develCost: Int, distance: Int): Long {
        var amount = develCost.toLong() * 5L
        repeat(distance) {
            if (amount > Long.MAX_VALUE / 2L) return Long.MAX_VALUE
            amount *= 2L
        }
        return amount
    }

    /** che_천도.php:121-124 — preReqTurn = distance*2. */
    fun getPreReqTurnForDistance(distance: Int): Int = distance * 2

    /** exp/ded magnitude for a (possibly null → 50) distance = 5*(distance*2 + 1). */
    fun expDedForDistance(distance: Int?): Int = 5 * (getPreReqTurnForDistance(distance ?: 50) + 1)

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        occupiedCity(), beChief(), suppliedCity(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val distance = (ctx.env["__distance"] as? Number)?.toInt()
            ?: (ctx.args["__distance"] as? Number)?.toInt() ?: 50
        val cost = getCost((ctx.env["develCost"] as Number).toInt(), distance)
        return listOf(
            occupiedCity(), occupiedDestCity(), beChief(), suppliedCity(), suppliedDestCity(),
            notAlreadyCapital(),  // ReqNationValue('capital','수도','!=',destCity,'이미 수도입니다.')
            reqNationResource(
                "ReqNationGold",
                requiredAmount(GameConst.basegold, cost),
                { it.gold },
                "국고가 부족합니다.",
            ),
            reqNationResource(
                "ReqNationRice",
                requiredAmount(GameConst.baserice, cost),
                { it.rice },
                "병량이 부족합니다.",
            ),
        )
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("destCityID" to (raw["destCityID"] as? Number)?.toInt())

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return
        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: return
        val destCityName = CityConstRegistry.of(context.env.mapName).byId(destCityId)?.name ?: ""
        val distance = context.cityDistance ?: 50
        val expDed = 5 * (getPreReqTurnForDistance(distance) + 1)

        // exp/ded += variable
        val expRes = addExperience(d.general, expDed.toDouble(), pipeline)
        val dedRes = addDedication(expRes.general, expDed.toDouble(), pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // nation.capital → dest; capset+1. NO gold/rice debit (che_천도.php:218-221).
        val capset = bumpCapset(nation.capset)
        val meta = LinkedHashMap(nation.meta)
        meta["capset"] = capset
        d.nation = nation.copy(capitalCityId = destCityId, capset = capset, meta = meta)

        val josaRo = JosaUtil.pick(destCityName, "로")
        val josaYi = JosaUtil.pick(context.generalName, "이")
        context.addLog("<G><b>$destCityName</b></>$josaRo 천도했습니다. <1>${context.date}</>")
        context.addGeneralHistoryLog("<G><b>$destCityName</b></>$josaRo <M>천도</>명령")
        context.addNationalHistoryLog(
            "<Y>${context.generalName}</>$josaYi <G><b>$destCityName</b></>$josaRo <M>천도</> 명령",
        )
        context.addGlobalActionLog(
            "<Y>${context.generalName}</>$josaYi <G><b>$destCityName</b></>$josaRo <M>천도</>를 명령하였습니다.")
        val josaYiNation = JosaUtil.pick(nation.name, "이")
        context.addGlobalHistoryLog(
            "<S><b>【천도】</b></><D><b>${nation.name}</b></>$josaYiNation <G><b>$destCityName</b></>$josaRo <M>천도</>하였습니다.",
        )
    }
}

private fun requiredAmount(base: Int, cost: Long): Long =
    if (cost > Long.MAX_VALUE - base.toLong()) Long.MAX_VALUE else base.toLong() + cost

private fun reqNationResource(
    constraintName: String,
    required: Long,
    resource: (Nation) -> Int,
    reason: String,
): Constraint = object : Constraint {
    override val name: String = constraintName
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.Nation(ctx.nationId ?: 0))

    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val nation = view.get(RequirementKey.Nation(ctx.nationId ?: 0)) as? Nation
            ?: return ConstraintResult.Unknown(requires(ctx))
        return if (resource(nation).toLong() >= required) ConstraintResult.Allow
        else ConstraintResult.Deny(reason)
    }
}

/**
 * `ReqNationValue('capital','수도','!=',destCity,'이미 수도입니다.')` — the dest city must not already
 * be the capital. The nation `capital` field is `capitalCityId` (not in the generic nationNumField
 * comparator table); a local nation-family constraint. Reads `ctx.args['destCityID']`.
 */
private fun notAlreadyCapital(): Constraint = object : Constraint {
    override val name = "ReqNationValue"
    override fun requires(ctx: ConstraintContext) =
        listOf(opensamguk.logic.constraints.RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: opensamguk.logic.constraints.StateView):
        opensamguk.logic.constraints.ConstraintResult {
        val n = view.get(opensamguk.logic.constraints.RequirementKey.Nation(ctx.nationId ?: 0))
            as? opensamguk.logic.domain.Nation
            ?: return opensamguk.logic.constraints.ConstraintResult.Unknown(requires(ctx))
        val destCityId = (ctx.args["destCityID"] as? Number)?.toInt()
        return if (n.capitalCityId != destCityId) opensamguk.logic.constraints.ConstraintResult.Allow
        else opensamguk.logic.constraints.ConstraintResult.Deny("이미 수도입니다.")
    }
}
