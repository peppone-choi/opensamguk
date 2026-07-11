package opensamguk.logic.actions.military

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.RandUtil
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
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.valueFit
import kotlin.math.log2

/**
 * che_화계 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_화계.php`.
 *
 * 계략(sabotage) 커맨드 base class. 적 dest 도시에 화계를 걸어 농업(agri)과 상업(comm)을 감소시키고
 * 장수들에게 부상을 입힌다. statType='intel', injuryGeneral=true.
 *
 * 하위 클래스(ChePagoe 등)는 [affectDestCity]를 오버라이드하여 도시에 미치는 효과를 재정의한다.
 *
 * argTest(che_화계.php:31-46): destCityID 존재 검증 → canonical {destCityID}.
 *
 * fullConditionConstraints(che_화계.php:138-149), PHP ORDER:
 *   [NotBeNeutral, OccupiedCity, SuppliedCity, NotOccupiedDestCity, NotNeutralDestCity,
 *    ReqGeneralGold, ReqGeneralRice, DisallowDiplomacyBetweenStatus([7])].
 *
 * minConditionConstraints(che_화계.php:119-125), PHP ORDER:
 *   [NotBeNeutral, OccupiedCity, SuppliedCity, ReqGeneralGold, ReqGeneralRice].
 *
 * run() 흐름 (che_화계.php:248-341):
 *   1. distance = searchDistance(actorCity, 5, false)[destCity] ?? 99
 *   2. prob = sabotageDefaultProb + attackProb - defenceProb
 *   3. prob /= distance; prob = valueFit(prob, 0, 0.5)
 *   4. DRAW1: rng.nextBool(prob) → false면 실패 분기 (골든 캡처 대상)
 *   5. 실패: 로그 + DRAW2(nextRangeInt 1,100) + DRAW3(nextRangeInt 1,70) + cost 차감 + exp/ded + stat_exp+1 + lastTurn
 *   6. 성공: SabotageInjury + affectDestCity(서브클스 오버라이드) + item + DRAW4/5 + cost + exp/ded + stat_exp+1 + rank+1 + lastTurn + staticEvent
 */
fun cheHwagye(pipeline: GeneralActionPipeline, maxLevel: Int = 255): CheHwagye =
    CheHwagye(pipeline, maxLevel)

open class CheHwagye(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key: String = "che_화계"
    override val name: String = "화계"
    override val category: String = "계략"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destCityID" to "int")

    /** 하위 클래스가 오버라이드할 statType. 화계는 "intel", 파괴는 "strength". */
    protected open val statType: String = "intel"

    /** 하위 클래스가 오버라이드할 부상 플래그. 화계는 true, 부상 없는 계략은 false. */
    protected open val injuryGeneral: Boolean = true

    /** che_화계.php:174-179 getCost — [develCost*5, develCost*5]. */
    fun getCost(env: opensamguk.logic.domain.WorldEnv): Pair<Int, Int> {
        val cost = env.develCost * 5
        return cost to cost
    }

    /** che_화계.php:31-46 argTest. */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("destCityID")) return null
        val destCityID = (raw["destCityID"] as? Number)?.toInt() ?: return null
        if (CityConst.byId(destCityID) == null) return null
        return linkedMapOf("destCityID" to destCityID)
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> {
        val cost = (ctx.env["develCost"] as? Number)?.toInt()?.times(5) ?: 0
        return listOf(
            notBeNeutral(),
            occupiedCity(),
            suppliedCity(),
            reqGeneralGold { _, _ -> cost },
            reqGeneralRice { _, _ -> cost },
        )
    }

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val cost = (ctx.env["develCost"] as? Number)?.toInt()?.times(5) ?: 0
        return listOf(
            notBeNeutral(),
            occupiedCity(),
            suppliedCity(),
            notOccupiedDestCity(),
            notNeutralDestCity(),
            reqGeneralGold { _, _ -> cost },
            reqGeneralRice { _, _ -> cost },
            disallowDiplomacyBetweenStatus(linkedMapOf(7 to "불가침국입니다.")),
        )
    }

    /** che_화계.php:48-67 calcSabotageAttackProb — statType='intel' for 화계. */
    private fun calcSabotageAttackProb(general: General): Double {
        val genScore = getStatValue(general, statType, pipeline, maxLevel, withInjury = true, useFloor = true)
        var prob = genScore / GameConst.sabotageProbCoefByStat.toDouble()
        prob = pipeline.onCalcDomestic(general, "계략", "success", prob)
        return prob
    }

    /** che_화계.php:69-107 calcSabotageDefenceProb. */
    private fun calcSabotageDefenceProb(destCity: City, destGenerals: List<General>): Double {
        var maxGenScore = 0.0
        var probCorrection = 0.0
        var affectGeneralCount = 0
        for (dg in destGenerals) {
            if (dg.nationId != destCity.nationId) continue
            affectGeneralCount++
            val genScore = getStatValue(dg, statType, pipeline, maxLevel, withInjury = true, useFloor = true)
            maxGenScore = maxOf(maxGenScore, genScore)
            probCorrection = pipeline.onCalcStat(dg, "sabotageDefence", probCorrection)
        }
        var prob = maxGenScore / GameConst.sabotageProbCoefByStat.toDouble()
        prob += probCorrection
        prob += (log2(affectGeneralCount + 1.0) - 1.25) * GameConst.sabotageDefenceCoefByGeneralCnt
        prob += destCity.security.toDouble() / destCity.securityMax / 5.0
        prob += if (destCity.supplyState != 0) 0.1 else 0.0
        return prob
    }

    /**
     * 성공 시 dest 도시에 미치는 효과. 하위 클래스가 오버라이드한다.
     *
     * PHP che_화계.php:209-246 affectDestCity:
     *   - agriAmount = valueFit(rng.nextRangeInt(min,max), null, destCity['agri'])
     *   - commAmount = valueFit(rng.nextRangeInt(min,max), null, destCity['comm'])
     *   - destCity['agri'] -= agriAmount; destCity['comm'] -= commAmount
     *   - DB::update city state=32, agri, comm
     *   - global log: "{destCityName}{이} 불타고 있습니다."
     *   - action log: "{destCityName}에 {commandName}{이} 성공했습니다. {date}"
     *   - plain log: "도시의 농업이 {agriAmount}, 상업이 {commAmount}만큼 감소하고, 장수 {injuryCount}명이 부상 당했습니다."
     *
     * @param rng RNG draw-for-draw 타겟
     * @param destCity 대상 도시 (mutable draft의 destCity)
     * @param destCityName 대상 도시 이름
     * @param commandName 명령 이름
     * @param injuryCount 부상당한 장수 수
     * @param context resolve context (로그 기록용)
     * @return agriAmount, commAmount 쌍 (로그 출력용)
     */
    protected open fun affectDestCity(
        rng: RandUtil,
        destCity: City,
        destCityName: String,
        commandName: String,
        injuryCount: Int,
        context: GeneralActionResolveContext,
    ): Pair<Int, Int> {
        val agriAmount = valueFit(
            rng.nextRangeInt(GameConst.sabotageDamageMin, GameConst.sabotageDamageMax).toDouble(),
            null,
            destCity.agriculture.toDouble(),
        ).toInt()
        val commAmount = valueFit(
            rng.nextRangeInt(GameConst.sabotageDamageMin, GameConst.sabotageDamageMax).toDouble(),
            null,
            destCity.commerce.toDouble(),
        ).toInt()

        // PHP che_화계.php:237-239 — 조사 둘로 분리: 방송은 도시명 기준, 액션로그는 명령명 기준.
        val josaCity = JosaUtil.pick(destCityName, "이")
        context.addGlobalActionLog("<G><b>$destCityName</b></>${josaCity} 불타고 있습니다.")
        val josaCmd = JosaUtil.pick(commandName, "이")
        context.addLog("<G><b>$destCityName</b></>에 $commandName$josaCmd 성공했습니다. <1>${context.date}</>")
        context.addActionPlainLog(
            "도시의 농업이 <C>${numberFormat(agriAmount)}</>, 상업이 <C>${numberFormat(commAmount)}</>만큼 감소하고, 장수 <C>$injuryCount</>명이 부상 당했습니다.",
        )

        return agriAmount to commAmount
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g = d.general
        val rng = context.rng
        val env = context.env

        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: return
        val destCity = d.destCity ?: return
        val destCityName = CityConst.byId(destCityId)?.name ?: ""
        val commandName = name
        val st = statType

        val distance = context.cityDistance ?: 99

        // prob 계산 (che_화계.php:284-286)
        var prob = GameConst.sabotageDefaultProb + calcSabotageAttackProb(g) - calcSabotageDefenceProb(destCity, context.candidateGenerals)
        prob /= distance
        prob = valueFit(prob, 0.0, 0.5)

        // DRAW1: nextBool(prob) (che_화계.php:288)
        if (!rng.nextBool(prob)) {
            // 실패 분기 (che_화계.php:289-305)
            val josaYi = JosaUtil.pick(commandName, "이")
            context.addLog("<G><b>$destCityName</b></>에 $commandName$josaYi 실패했습니다. <1>${context.date}</>")

            // DRAW2: nextRangeInt(1, 100) (che_화계.php:292)
            val exp = rng.nextRangeInt(1, 100)
            // DRAW3: nextRangeInt(1, 70) (che_화계.php:293)
            val ded = rng.nextRangeInt(1, 70)

            val (reqGold, reqRice) = getCost(env)

            d.general = g.copy(
                gold = maxOf(0, g.gold - reqGold),
                rice = maxOf(0, g.rice - reqRice),
                experience = g.experience + exp,
                dedication = g.dedication + ded,
                meta = withMeta(g.meta, "${st}_exp" to metaInt(g.meta, "${st}_exp") + 1),
                lastTurn = LastTurn(name, arg = linkedMapOf("destCityID" to destCityId)),
            )
            return
        }

        // 성공 분기 (che_화계.php:307-340)
        val injuryCount = if (injuryGeneral) {
            sabotageInjury(rng, context.candidateGenerals, "계략", context, pipeline)
        } else {
            0
        }

        val (agriAmount, commAmount) = affectDestCity(rng, destCity, destCityName, commandName, injuryCount, context)

        // destCity mutation — draft에 반영 (ChangeRecorder가 diff로 감지)
        d.destCity = destCity.copy(
            agriculture = destCity.agriculture - agriAmount,
            commerce = destCity.commerce - commAmount,
            state = 32,
        )

        consumeSabotageItemIfNeeded(context)

        // DRAW4: nextRangeInt(201, 300) (che_화계.php:323)
        val exp = rng.nextRangeInt(201, 300)
        // DRAW5: nextRangeInt(141, 210) (che_화계.php:324)
        val ded = rng.nextRangeInt(141, 210)

        val (reqGold, reqRice) = getCost(env)

        val postItemGeneral = d.general
        d.general = postItemGeneral.copy(
            gold = maxOf(0, postItemGeneral.gold - reqGold),
            rice = maxOf(0, postItemGeneral.rice - reqRice),
            experience = postItemGeneral.experience + exp,
            dedication = postItemGeneral.dedication + ded,
            meta = withMeta(postItemGeneral.meta, "${st}_exp" to metaInt(postItemGeneral.meta, "${st}_exp") + 1),
            lastTurn = LastTurn(name, arg = linkedMapOf("destCityID" to destCityId)),
        )

        d.rankIncrements.add(opensamguk.logic.actions.GeneralRankIncrement(g.id, "firenum", 1))

        // StaticEventHandler (che_화계.php:338)
        // scenario 1010 빈 맵이 정본이므로 no-op.
        opensamguk.logic.event.StaticEventHandler.handleEvent(
            d.general,
            null,
            key,
            mapOf("year" to env.year, "startYear" to env.startYear, "develCost" to env.develCost),
            linkedMapOf("destCityID" to destCityId),
        )
    }
}

private data class SabotageConsumableItem(val displayName: String, val rawName: String)

private val sabotageConsumableItems = mapOf(
    "che_계략_이추" to SabotageConsumableItem("이추(계략)", "이추"),
    "che_계략_향낭" to SabotageConsumableItem("향낭(계략)", "향낭"),
)

internal fun consumeSabotageItemIfNeeded(context: GeneralActionResolveContext) {
    val current = context.draft.general
    val item = sabotageConsumableItems[current.item] ?: return
    val josaUl = JosaUtil.pick(item.rawName, "을")
    context.addActionPlainLog("<C>${item.displayName}</>$josaUl 사용!")
    context.draft.general = current.copy(item = "None")
}
