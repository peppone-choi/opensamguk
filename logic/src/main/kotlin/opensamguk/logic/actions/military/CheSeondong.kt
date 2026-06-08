package opensamguk.logic.actions.military

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.disallowDiplomacyBetweenStatus
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notNeutralDestCity
import opensamguk.logic.constraints.notOccupiedDestCity
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.valueFit
import kotlin.math.ln

/**
 * che_선동 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_선동.php`.
 *
 * 계략(sabotage) GeneralCommand 계열. `statType='leadership'`, `injuryGeneral=true`.
 * 적 도시의 치안(secu)과 민심(trust)을 감소시킨다.
 *
 * PHP run() 흐름(che_화계.php:248-341 기반 + che_선동.php:affectDestCity 오버라이드):
 *  1) prob 계산(calcSabotageAttackProb - calcSabotageDefenceProb) / distance → valueFit[0,0.5]
 *  2) nextBool(prob) — DRAW1
 *     - false(실패): 실패 로그 1줄 + nextRangeInt(1,100)=exp DRAW2 + nextRangeInt(1,70)=ded DRAW3
 *       + cost 차감 + exp/ded + stat_exp += 1 + lastTurn
 *     - true(성공): SabotageInjury(injuryGeneral=true) + affectDestCity()
 *       - nextRangeInt(min,max) → secuAmount DRAW4
 *       - nextRange(min,max) / 50 → trustAmount DRAW5
 *       - destCity secu/trust 차감, state=32
 *       - global log + plain log
 *       - nextRangeInt(201,300)=exp, nextRangeInt(141,210)=ded
 *       - cost 차감 + exp/ded + stat_exp += 1 + lastTurn
 */
class CheSeondong(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key = "che_선동"
    override val name = "선동"
    override val category = "계략"
    override val argsSchema: Map<String, Any?> = linkedMapOf("destCityID" to "int")

    /** che_화계.php:174-179 getCost = [develCost*5, develCost*5] */
    fun getCost(env: opensamguk.logic.domain.WorldEnv): Pair<Int, Int> {
        val cost = env.develCost * 5
        return cost to cost
    }

    /** che_화계.php:31-46 argTest */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("destCityID")) return null
        val destCityID = (raw["destCityID"] as? Number)?.toInt() ?: return null
        if (CityConst.byId(destCityID) == null) return null
        return linkedMapOf("destCityID" to destCityID)
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> {
        val cost = ((ctx.env["develCost"] as? Number)?.toInt() ?: 0) * 5
        return listOf(
            notBeNeutral(), occupiedCity(), suppliedCity(),
            reqGeneralGold { _, _ -> cost },
            reqGeneralRice { _, _ -> cost },
        )
    }

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val cost = ((ctx.env["develCost"] as? Number)?.toInt() ?: 0) * 5
        return listOf(
            notBeNeutral(), occupiedCity(), suppliedCity(),
            notNeutralDestCity(), notOccupiedDestCity(),
            reqGeneralGold { _, _ -> cost },
            reqGeneralRice { _, _ -> cost },
            disallowDiplomacyBetweenStatus(linkedMapOf(7 to "불가침국입니다.")),
        )
    }

    /** che_화계.php:48-67 calcSabotageAttackProb — statType='leadership' */
    private fun calcSabotageAttackProb(general: opensamguk.logic.domain.General): Double {
        val genScore = getStatValue(general, "leadership", pipeline, maxLevel)
        var prob = genScore / GameConst.sabotageProbCoefByStat
        prob = pipeline.onCalcDomestic(general, "계략", "success", prob)
        return prob
    }

    /** che_화계.php:69-107 calcSabotageDefenceProb. */
    private fun calcSabotageDefenceProb(
        destCityGeneralList: List<opensamguk.logic.domain.General>,
        destCity: opensamguk.logic.domain.City,
        destNationId: Int,
    ): Double {
        var maxGenScore = 0.0
        var probCorrection = 0.0
        var affectGeneralCount = 0
        for (destGeneral in destCityGeneralList) {
            if (destGeneral.nationId != destNationId) continue
            affectGeneralCount++
            val genScore = getStatValue(destGeneral, "leadership", pipeline, maxLevel)
            maxGenScore = maxOf(maxGenScore, genScore)
            probCorrection = pipeline.onCalcStat(destGeneral, "sabotageDefence", probCorrection)
        }
        var prob = maxGenScore / GameConst.sabotageProbCoefByStat
        prob += probCorrection
        prob += (ln(affectGeneralCount + 1.0) / ln(2.0) - 1.25) * GameConst.sabotageDefenceCoefByGeneralCnt
        prob += (destCity.security.toDouble() / destCity.securityMax) / 5.0
        prob += if (destCity.supplyState != 0) 0.1 else 0.0
        return prob
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g0 = d.general
        val rng = context.rng
        val env = context.env
        val date = context.date

        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: return
        val destCity = d.destCity ?: return
        val destCityName = CityConst.byId(destCityId)?.name ?: ""
        val destNationId = destCity.nationId
        val commandName = name
        val statType = "leadership"

        val dist = context.cityDistance?.toDouble() ?: 1.0

        val destCityGeneralList = context.candidateGenerals.filter { it.nationId == destNationId }

        var prob = GameConst.sabotageDefaultProb +
                calcSabotageAttackProb(g0) -
                calcSabotageDefenceProb(destCityGeneralList, destCity, destNationId)
        prob /= dist
        prob = valueFit(prob, 0.0, 0.5)

        // --- FAIL branch ---
        if (!rng.nextBool(prob)) {                                           // DRAW1
            val josaYi = JosaUtil.pick(commandName, "이")
            context.addLog("<G><b>$destCityName</b></>에 $commandName$josaYi 실패했습니다. <1>$date</>")

            val exp = rng.nextRangeInt(1, 100)                               // DRAW2
            val ded = rng.nextRangeInt(1, 70)                                // DRAW3

            val (reqGold, reqRice) = getCost(env)
            d.general = g0.copy(
                gold = maxOf(0, g0.gold - reqGold),
                rice = maxOf(0, g0.rice - reqRice),
                experience = g0.experience + exp,
                dedication = g0.dedication + ded,
                meta = withMeta(g0.meta, "${statType}_exp" to metaInt(g0.meta, "${statType}_exp") + 1),
                lastTurn = LastTurn(name, linkedMapOf("destCityID" to destCityId)),
            )
            return
        }

        // --- SUCCESS branch ---
        // injuryGeneral = true → call sabotageInjury
        val injuryCount = sabotageInjury(rng, destCityGeneralList, commandName, context, pipeline)

        affectDestCity(context, rng, destCity, destCityName, destCityId, injuryCount)

        val exp = rng.nextRangeInt(201, 300)                                 // DRAWn
        val ded = rng.nextRangeInt(141, 210)                                 // DRAWn

        val (reqGold, reqRice) = getCost(env)
        var g = d.general
        g = g.copy(
            gold = maxOf(0, g.gold - reqGold),
            rice = maxOf(0, g.rice - reqRice),
            experience = g.experience + exp,
            dedication = g.dedication + ded,
            meta = withMeta(g.meta, "${statType}_exp" to metaInt(g.meta, "${statType}_exp") + 1),
            lastTurn = LastTurn(name, linkedMapOf("destCityID" to destCityId)),
        )
        d.general = g
    }

    /**
     * che_선동.php:20-60 affectDestCity — 선동 성공 시 적 도시 치안/민심 감소.
     *
     * Draw order (success branch):
     *   DRAW4 nextRangeInt(sabotageDamageMin, sabotageDamageMax) → secu amount
     *   DRAW5 nextRange(sabotageDamageMin, sabotageDamageMax) / 50 → trust amount
     */
    private fun affectDestCity(
        context: GeneralActionResolveContext,
        rng: opensamguk.common.rng.RandUtil,
        destCity: opensamguk.logic.domain.City,
        destCityName: String,
        destCityID: Int,
        injuryCount: Int,
    ) {
        val d = context.draft
        val commandName = name

        // secuAmount = valueFit(nextRangeInt(min,max), null, destCity.secu)
        val secuAmount = valueFit(
            rng.nextRangeInt(GameConst.sabotageDamageMin, GameConst.sabotageDamageMax).toDouble(),
            null,
            destCity.security.toDouble(),
        ).toInt()                                                               // DRAW4

        // trustAmount = valueFit(nextRange(min,max) / 50, null, destCity.trust)
        val trustAmount = valueFit(
            rng.nextRange(GameConst.sabotageDamageMin.toDouble(), GameConst.sabotageDamageMax.toDouble()) / 50.0,
            null,
            destCity.trust,
        )                                                                         // DRAW5

        // Update dest city: secu/trust reduced, state=32
        d.destCity = destCity.copy(
            security = maxOf(0, destCity.security - secuAmount),
            trust = maxOf(0.0, destCity.trust - trustAmount),
            meta = withMeta(destCity.meta, "state" to 32),
        )

        val secuAmountText = numberFormat(secuAmount)
        val trustAmountText = numberFormat(trustAmount.toInt())

        // Logs (PHP execution order)
        val josaYi = JosaUtil.pick(commandName, "이")
        context.addLog("<G><b>$destCityName</b></>에 $commandName$josaYi 성공했습니다. <1>${context.date}</>")
        context.addGlobalActionLog("<G><b>$destCityName</b></>의 백성들이 동요하고 있습니다.")
        context.addActionPlainLog("도시의 치안이 <C>$secuAmountText</>, 민심이 <C>$trustAmountText</>만큼 감소하고, 장수 <C>$injuryCount</>명이 부상 당했습니다.")
    }
}

fun cheSeondong(pipeline: GeneralActionPipeline, maxLevel: Int = 255) = CheSeondong(pipeline, maxLevel)
