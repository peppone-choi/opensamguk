package opensamguk.logic.actions.nation

import opensamguk.common.constants.CityConst
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
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_감축 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_감축.php`.
 *
 * Capital-shrink: REFUNDS the cost (gold/rice += cost), decrements the capital city by one level,
 * floors the developed stats (`greatest` math), and shifts ALL `*_max` down by the per-stat increment
 * (these may go negative — NO floor on max). preReqTurn=5 (exp/ded +30), postReqTurn=0. Bumps the
 * nation capset (the capset-seq term-stack invalidator) and writes the success result-turn (term=0).
 *
 * The capital is carried as `draft.destCity` (the precheck/daemon adapter sets destCity = capital,
 * mirroring PHP `setDestCity($this->nation['capital'])`).
 */
fun cheGamchuk(pipeline: GeneralActionPipeline): NationCommand = object : NationCommand() {
    override val key: String get() = "che_감축"
    override val name: String get() = "감축"

    override fun getPreReqTurn(): Int = 5

    /** che_감축.php:84-88 — develcost*500 + 60000/2 = develcost*500 + 30000 (gold == rice). */
    fun getCost(develCost: Int): Int =
        develCost * GameConst.expandCityCostCoef + GameConst.expandCityDefaultCost / 2

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        // che_감축.php:63 — origCityLevel = CityConst::byID(capital)->level (정적 시나리오 레벨 = 감축 하한).
        // 확장(증축)으로 정적 레벨 위로 키운 분만 되돌릴 수 있어 현재 level 이 origCityLevel 보다 커야 한다.
        val origCityLevel = ctx.destCityId?.let { CityConst.byId(it)?.level } ?: 0
        // che_감축.php:69-70 — 두 ReqDestCityValue (둘 다 '더이상 감축할 수 없습니다.'): level>4 + level>origCityLevel.
        return listOf(
            occupiedCity(), beChief(), suppliedCity(),
            reqDestCityValue("level", "규모", ">", 4, "더이상 감축할 수 없습니다."),
            reqDestCityValue("level", "규모", ">", origCityLevel, "더이상 감축할 수 없습니다."),
        )
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(notBeNeutral())

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return
        val capital = d.destCity ?: return
        val cost = getCost(context.env.develCost)

        // exp/ded += 5*(preReqTurn+1) (addExperience/addDedication fold through onCalcStat; plain level logs)
        val expRes = addExperience(d.general, expDedMagnitude().toDouble(), pipeline)
        val dedRes = addDedication(expRes.general, expDedMagnitude().toDouble(), pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // city decrements (che_감축.php:174-189). greatest floors current; *_max unfloored (can go negative).
        val popDec = GameConst.expandCityPopIncreaseAmount     // 100000
        val develDec = GameConst.expandCityDevelIncreaseAmount // 2000 (agri/comm/secu)
        val wallDec = GameConst.expandCityWallIncreaseAmount   // 2000 (def/wall)
        d.destCity = capital.copy(
            level = capital.level - 1,
            population = maxOf(capital.population - popDec, GameConst.minAvailableRecruitPop),
            agriculture = maxOf(capital.agriculture - develDec, 0),
            commerce = maxOf(capital.commerce - develDec, 0),
            security = maxOf(capital.security - develDec, 0),
            defense = maxOf(capital.defense - wallDec, 0),
            wall = maxOf(capital.wall - wallDec, 0),
            populationMax = capital.populationMax - popDec,
            agricultureMax = capital.agricultureMax - develDec,
            commerceMax = capital.commerceMax - develDec,
            securityMax = capital.securityMax - develDec,
            defenseMax = capital.defenseMax - wallDec,
            wallMax = capital.wallMax - wallDec,
        )

        // nation: capset+1, gold/rice REFUNDED (che_감축.php:192-196)
        d.nation = nation.copy(
            capset = bumpCapset(nation.capset),
            gold = nation.gold + cost,
            rice = nation.rice + cost,
        )

        // logs (che_감축.php:198 general action + :201 global action)
        val destName = CityConst.byId(capital.id)?.name ?: ""
        val josaUl = JosaUtil.pick(destName, "을")
        val josaYi = JosaUtil.pick(context.generalName, "이")
        context.addLog("<G><b>$destName</b></>$josaUl 감축했습니다. <1>${context.date}</>")
        context.addGlobalActionLog(
            "<Y>${context.generalName}</>$josaYi <G><b>$destName</b></>$josaUl <M>감축</>하였습니다.")
    }
}
