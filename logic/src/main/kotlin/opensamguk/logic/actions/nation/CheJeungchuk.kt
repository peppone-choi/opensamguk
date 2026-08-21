package opensamguk.logic.actions.nation

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.bumpCapset
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqDestCityValue
import opensamguk.logic.constraints.reqNationGold
import opensamguk.logic.constraints.reqNationRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry

/**
 * che_증축 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_증축.php`.
 *
 * Capital-expand: SPENDS the cost (gold/rice -= cost), increments the capital city by one level, and
 * shifts ALL `*_max` UP by the per-stat increment — the CURRENT developed stats are UNCHANGED.
 * preReqTurn=5 (exp/ded +30), postReqTurn=0. Bumps the nation capset, writes the success result-turn.
 *
 * The capital is carried as `draft.destCity` (the adapter sets destCity = capital).
 */
fun cheJeungchuk(pipeline: GeneralActionPipeline): NationCommand = object : NationCommand() {
    override val key: String get() = "che_증축"
    override val name: String get() = "증축"

    override fun getPreReqTurn(): Int = 5

    /** che_증축.php:82-86 — develcost*500 + 60000 (gold == rice). */
    fun getCost(develCost: Int): Int =
        develCost * GameConst.expandCityCostCoef + GameConst.expandCityDefaultCost

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val cost = getCost((ctx.env["develCost"] as Number).toInt())
        return listOf(
            occupiedCity(), beChief(), suppliedCity(),
            reqDestCityValue("level", "규모", ">", 3, "수진, 진, 관문에서는 불가능합니다."),
            reqDestCityValue("level", "규모", "<", 8, "더이상 증축할 수 없습니다."),
            reqNationGold { _, _ -> GameConst.basegold + cost },
            reqNationRice { _, _ -> GameConst.baserice + cost },
        )
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(notBeNeutral())

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return
        val capital = d.destCity ?: return
        val cost = getCost(context.env.develCost)

        val expRes = addExperience(d.general, expDedMagnitude().toDouble(), pipeline)
        val dedRes = addDedication(expRes.general, expDedMagnitude().toDouble(), pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // city: level+1; *_max += increments; current UNCHANGED (che_증축.php:173-181)
        val popInc = GameConst.expandCityPopIncreaseAmount
        val develInc = GameConst.expandCityDevelIncreaseAmount
        val wallInc = GameConst.expandCityWallIncreaseAmount
        d.destCity = capital.copy(
            level = capital.level + 1,
            populationMax = capital.populationMax + popInc,
            agricultureMax = capital.agricultureMax + develInc,
            commerceMax = capital.commerceMax + develInc,
            securityMax = capital.securityMax + develInc,
            defenseMax = capital.defenseMax + wallInc,
            wallMax = capital.wallMax + wallInc,
        )

        // nation: capset+1, gold/rice SPENT (che_증축.php:184-188)
        d.nation = nation.copy(
            capset = bumpCapset(nation.capset),
            gold = nation.gold - cost,
            rice = nation.rice - cost,
        )

        val destName = CityConstRegistry.of(context.env.mapName).byId(capital.id)?.name ?: ""
        val josaUl = JosaUtil.pick(destName, "을")
        val josaYi = JosaUtil.pick(context.generalName, "이")
        context.addLog("<G><b>$destName</b></>$josaUl 증축했습니다. <1>${context.date}</>")
        context.addGlobalActionLog(
            "<Y>${context.generalName}</>$josaYi <G><b>$destName</b></>$josaUl <M>증축</>하였습니다.")
    }
}
