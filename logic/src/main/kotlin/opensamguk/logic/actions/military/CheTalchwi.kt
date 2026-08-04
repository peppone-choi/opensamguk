package opensamguk.logic.actions.military

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralRankIncrement
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
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.truncate

/**
 * che_탈취 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_탈취.php`.
 *
 * 계략(sabotage) GeneralCommand 계열. `che_화계`를 상속하며 `statType='strength'`,
 * `injuryGeneral=false`. 적 도시의 금/쌀을 탈취한다.
 *
 * PHP run() 흐름(che_화계.php:248-341 기반 + che_탈취.php:affectDestCity 오버라이드):
 *  1) prob 계산(calcSabotageAttackProb - calcSabotageDefenceProb) / distance → valueFit[0,0.5]
 *  2) nextBool(prob) — DRAW1
 *     - false(실패): 실패 로그 1줄 + nextRangeInt(1,100)=exp DRAW2 + nextRangeInt(1,70)=ded DRAW3
 *       + cost 차감 + exp/ded + stat_exp += 1 + lastTurn
 *     - true(성공): injuryCount=0 ($injuryGeneral=false) → affectDestCity()
 *       - nextRangeInt(100,800) × level × yearCoef × ratio → DRAW4(gold), DRAW5(rice)
 *       - dest nation gold/rice 차감, actor nation 70% 회수, actor 30% 회수
 *       - global log + personal logs
 *       - existing sabotage consumable item seam, if present
 *       - nextRangeInt(201,300)=exp, nextRangeInt(141,210)=ded
 *       - cost 차감 + exp/ded + stat_exp += 1 + lastTurn
 *
 * 골든(che_탈취-fixtures.json): fail branch, draw_count=3.
 *   DRAW1 nextBool(prob=0.257) → false
 *   DRAW2 nextRangeInt(1,100) → 15 (exp)
 *   DRAW3 nextRangeInt(1,70) → 64 (ded)
 */
class CheTalchwi(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key = "che_탈취"
    override val name = "탈취"
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

    /** che_화계.php:48-67 calcSabotageAttackProb — statType='strength' */
    private fun calcSabotageAttackProb(general: opensamguk.logic.domain.General): Double {
        val genScore = getStatValue(general, "strength", pipeline, maxLevel)
        var prob = genScore / GameConst.sabotageProbCoefByStat
        prob = pipeline.onCalcDomestic(general, "계략", "success", prob)
        return prob
    }

    /**
     * che_화계.php:69-107 calcSabotageDefenceProb.
     * destCityGeneralList는 메모리 드래프트에 없어 candidateGenerals + destCity 정보로 재구성.
     */
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
            val genScore = getStatValue(destGeneral, "strength", pipeline, maxLevel)
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
        val month = context.month
        val date = context.date

        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: return
        val destCity = d.destCity ?: return
        val destCityName = CityConst.byId(destCityId)?.name ?: ""
        val destNationId = destCity.nationId
        val commandName = name
        val statType = "strength"

        // distance — golden fixture 주입값 사용, 없으면 기본 1
        val dist = context.cityDistance?.toDouble() ?: 1.0

        // dest city 적 장수 리스트 — candidateGenerals에서 destNation 소속만 필터
        val destCityGeneralList = context.candidateGenerals.filter { it.nationId == destNationId }

        // prob 계산 (che_화계.php:284-286)
        var prob = GameConst.sabotageDefaultProb +
                calcSabotageAttackProb(g0) -
                calcSabotageDefenceProb(destCityGeneralList, destCity, destNationId)
        prob /= dist
        prob = valueFit(prob, 0.0, 0.5)

        // --- FAIL branch (golden captured) ---
        if (!rng.nextBool(prob)) {                                           // DRAW1
            val josaYi = JosaUtil.pick(commandName, "이")
            context.addLog("<G><b>$destCityName</b></>에 $commandName$josaYi 실패했습니다. <1>$date</>")

            val exp = rng.nextRangeInt(1, 100)                               // DRAW2
            val ded = rng.nextRangeInt(1, 70)                                // DRAW3

            val (reqGold, reqRice) = getCost(env)
            var g = g0.copy(
                gold = maxOf(0, g0.gold - reqGold),
                rice = maxOf(0, g0.rice - reqRice),
            )
            val experience = addExperience(g, exp.toDouble(), pipeline)
            g = experience.general
            experience.plainLog?.let { context.addPlainLog(it) }
            val dedication = addDedication(g, ded.toDouble(), pipeline)
            g = dedication.general
            dedication.plainLog?.let { context.addPlainLog(it) }
            g = g.copy(
                meta = withMeta(g.meta, "${statType}_exp" to metaInt(g.meta, "${statType}_exp") + 1),
                lastTurn = LastTurn(name, linkedMapOf("destCityID" to destCityId)),
            )
            val statChange = checkStatChange(g)
            d.general = statChange.general
            statChange.plainLogs.forEach { context.addPlainLog(it) }
            return
        }

        // --- SUCCESS branch (not in golden, faithfully ported) ---
        // injuryGeneral = false → injuryCount = 0 (che_화계.php:308-312)
        val injuryCount = 0

        affectDestCity(context, rng, destCity, destCityName, destCityId, destNationId, commandName, date, injuryCount)

        consumeSabotageItemIfNeeded(context)

        val exp = rng.nextRangeInt(201, 300)                                 // DRAWn (success branch)
        val ded = rng.nextRangeInt(141, 210)                                 // DRAWn (success branch)

        val (reqGold, reqRice) = getCost(env)
        var g = d.general
        g = g.copy(
            gold = maxOf(0, g.gold - reqGold),
            rice = maxOf(0, g.rice - reqRice),
        )
        val experience = addExperience(g, exp.toDouble(), pipeline)
        g = experience.general
        experience.plainLog?.let { context.addPlainLog(it) }
        val dedication = addDedication(g, ded.toDouble(), pipeline)
        g = dedication.general
        dedication.plainLog?.let { context.addPlainLog(it) }
        g = g.copy(
            meta = withMeta(g.meta, "${statType}_exp" to metaInt(g.meta, "${statType}_exp") + 1),
            lastTurn = LastTurn(name, linkedMapOf("destCityID" to destCityId)),
        )
        d.general = g
        d.rankIncrements.add(GeneralRankIncrement(g0.id, "firenum", 1))
        StaticEventHandler.handleEvent(
            d.general,
            null,
            key,
            mapOf("year" to env.year, "startYear" to env.startYear, "develCost" to env.develCost),
            context.args,
        )
        val statChange = checkStatChange(d.general)
        d.general = statChange.general
        statChange.plainLogs.forEach { context.addPlainLog(it) }
    }

    /**
     * che_탈취.php:19-101 affectDestCity — 탈취 성공 시 적 도시 금/쌀 탈취.
     *
     * Draw order (success branch only, not in golden):
     *   DRAW4 nextRangeInt(100,800) → gold base
     *   DRAW5 nextRangeInt(100,800) → rice base
     */
    private fun affectDestCity(
        context: GeneralActionResolveContext,
        rng: opensamguk.common.rng.RandUtil,
        destCity: opensamguk.logic.domain.City,
        destCityName: String,
        destCityID: Int,
        destNationID: Int,
        commandName: String,
        date: String,
        injuryCount: Int,
    ) {
        val d = context.draft
        val g0 = d.general
        val nationID = g0.nationId
        val env = context.env

        // yearCoef = sqrt(1 + (year - startYear) / 4) / 2 (che_탈취.php:36)
        val yearCoef = sqrt(1.0 + (env.year - env.startYear) / 4.0) / 2.0
        val commRatio = destCity.commerce.toDouble() / destCity.commerceMax
        val agriRatio = destCity.agriculture.toDouble() / destCity.agricultureMax

        // gold/rice 계산 (che_탈취.php:39-40)
        val goldRaw = rng.nextRangeInt(GameConst.sabotageDamageMin, GameConst.sabotageDamageMax).toDouble() *
                destCity.level * yearCoef * (0.25 + commRatio / 4.0)               // DRAW4
        val riceRaw = rng.nextRangeInt(GameConst.sabotageDamageMin, GameConst.sabotageDamageMax).toDouble() *
                destCity.level * yearCoef * (0.25 + agriRatio / 4.0)               // DRAW5

        var gold = goldRaw
        var rice = riceRaw

        // supply 상태일 때는 국고에서 차감, 아니면 도시 상업/농업에서 차감 (che_탈취.php:42-71)
        if (destCity.supplyState != 0) {
            // dest nation gold/rice 차감 — draft.destNation에서 읽음
            val destNation = d.destNation
            if (destNation != null) {
                var destNationGold = destNation.gold - gold
                var destNationRice = destNation.rice - rice

                if (destNationGold < GameConst.minNationalGold) {
                    gold += destNationGold - GameConst.minNationalGold
                    destNationGold = GameConst.minNationalGold.toDouble()
                }
                if (destNationRice < GameConst.minNationalRice) {
                    rice += destNationRice - GameConst.minNationalRice
                    destNationRice = GameConst.minNationalRice.toDouble()
                }

                d.destNation = destNation.copy(gold = destNationGold.toInt(), rice = destNationRice.toInt())
                // city state = 34 (탈취 상태)
                d.destCity = destCity.copy(state = 34)
            }
        } else {
            // supply 없음 → 도시 상업/농업 직접 차감
            d.destCity = destCity.copy(
                commerce = maxOf(0, truncate(destCity.commerce - gold / 12.0).toInt()),
                agriculture = maxOf(0, truncate(destCity.agriculture - rice / 12.0).toInt()),
                state = 34,
            )
        }

        // 본국으로 70% 회수, 재야(nation==0)이면 본인이 전량 소유 (che_탈취.php:74-84)
        val goldRounded = phpRound(gold)
        val riceRounded = phpRound(rice)
        val goldToNation = phpRound(gold * 0.7)
        val riceToNation = phpRound(rice * 0.7)

        if (nationID != 0) {
            d.nation = d.nation?.copy(
                gold = (d.nation?.gold ?: 0) + goldToNation,
                rice = (d.nation?.rice ?: 0) + riceToNation,
            )
            d.general = d.general.copy(
                gold = d.general.gold + (goldRounded - goldToNation),
                rice = d.general.rice + (riceRounded - riceToNation),
            )
        } else {
            d.general = d.general.copy(
                gold = d.general.gold + goldRounded,
                rice = d.general.rice + riceRounded,
            )
        }

        // city state 복원 (32) 및 agri/comm 원래 값으로 (che_탈취.php:86-90)
        // PHP는 DB 업데이트 후 city['agri']/city['comm']을 원래 값으로 되돌림
        d.destCity = d.destCity?.copy(
            state = 32,
            agriculture = destCity.agriculture,
            commerce = destCity.commerce,
        )

        val goldText = numberFormat(goldRounded)
        val riceText = numberFormat(riceRounded)

        // 로그 (che_탈취.php:96-100)
        context.addGlobalActionLog("<G><b>$destCityName</b></>에서 금과 쌀을 도둑맞았습니다.")
        val josaYi = JosaUtil.pick(commandName, "이")
        context.addLog("<G><b>$destCityName</b></>에 $commandName$josaYi 성공했습니다. <1>$date</>")
        context.addActionPlainLog("금<C>$goldText</> 쌀<C>$riceText</>을 획득했습니다.")
    }
}

fun cheTalchwi(pipeline: GeneralActionPipeline, maxLevel: Int = 255) = CheTalchwi(pipeline, maxLevel)
