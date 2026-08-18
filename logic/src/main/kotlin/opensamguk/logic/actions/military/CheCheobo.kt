package opensamguk.logic.actions.military

import opensamguk.common.constants.CityConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notOccupiedDestCity
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.numberFormat
import opensamguk.logic.world.CalcCityDistance

/**
 * che_첩보 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_첩보.php`.
 *
 * 첩보(spy/reconnaissance) GeneralCommand. 적 도시 정보를 수집하고 국가 spy 맵에 기록한다.
 * getCost = [develcost*3, develcost*3]. draw_count = 2 (nextRangeInt(1,100), nextRangeInt(1,70)).
 *
 * PHP run() 흐름(che_첩보.php:123-221):
 *  1) searchDistance로 거리 측정 → dist (1=많은 정보, 2=어느 정도, 3+=소문만)
 *  2) pushGlobalActionLog — "누군가가 {destCityName}{을} 살피는 것 같습니다."
 *  3) dist 분기별 로그:
 *     - dist<=1: 정보 많음 + cityBrief(RAWTEXT) + cityDevel(RAWTEXT) + 병종 분포(RAWTEXT) + 기술 비교
 *     - dist==2: 정보 어느 정도 + cityBrief(RAWTEXT) + cityDevel(RAWTEXT)
 *     - dist>=3: 소문만 + cityBrief(RAWTEXT)
 *  4) nation spy = {"destCityID":3} 기록
 *  5) nextRangeInt(1,100)=exp DRAW1, nextRangeInt(1,70)=ded DRAW2
 *  6) cost 차감 + exp/ded + leadership_exp += 1 + lastTurn
 */
class CheCheobo(
    private val pipeline: GeneralActionPipeline,
) : GeneralActionDefinition {
    override val key = "che_첩보"
    override val name = "첩보"
    override val category = "계략"
    override val argsSchema: Map<String, Any?> = linkedMapOf("destCityID" to "int")

    /** che_첩보.php:96-100 getCost = [develcost*3, develcost*3] */
    fun getCost(env: opensamguk.logic.domain.WorldEnv): Pair<Int, Int> {
        val cost = env.develCost * 3
        return cost to cost
    }

    /** che_첩보.php:26-41 argTest */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("destCityID")) return null
        val destCityID = (raw["destCityID"] as? Number)?.toInt() ?: return null
        if (CityConst.byId(destCityID) == null) return null
        return linkedMapOf("destCityID" to destCityID)
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> {
        val cost = ((ctx.env["develCost"] as? Number)?.toInt() ?: 0) * 3
        return listOf(
            notBeNeutral(),
            reqGeneralGold { _, _ -> cost },
            reqGeneralRice { _, _ -> cost },
        )
    }

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val cost = ((ctx.env["develCost"] as? Number)?.toInt() ?: 0) * 3
        return listOf(
            notBeNeutral(),
            notOccupiedDestCity(),
            reqGeneralGold { _, _ -> cost },
            reqGeneralRice { _, _ -> cost },
        )
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
        val josaUl = JosaUtil.pick(destCityName, "을")

        // searchDistance로 거리 측정 (che_첩보.php:145)
        val dist = CalcCityDistance.searchDistance(g0.cityId, 2)[destCityId] ?: 3

        // dest city 적 장수 정보 — candidateGenerals에서 destNation 소속만 필터
        val destCityGeneralList = context.candidateGenerals.filter { it.nationId == destCity.nationId }
        val totalCrew = destCityGeneralList.sumOf { it.crew }
        val totalGenCnt = destCityGeneralList.size
        val byCrewType = destCityGeneralList.groupBy { it.crewTypeId }

        // number_format 텍스트 (che_첩보.php:152-158)
        val popText = numberFormat(destCity.population)
        val trustText = "%,.1f".format(destCity.trust)
        val agriText = numberFormat(destCity.agriculture)
        val commText = numberFormat(destCity.commerce)
        val secuText = numberFormat(destCity.security)
        val defText = numberFormat(destCity.defense)
        val wallText = numberFormat(destCity.wall)

        val cityBrief = "【<G>$destCityName</>】주민:$popText, 민심:$trustText, 장수:$totalGenCnt, 병력:$totalCrew"
        val cityDevel = "【<M>첩보</>】농업:$agriText, 상업:$commText, 치안:$secuText, 수비:$defText, 성벽:$wallText"

        // global broadcast (che_첩보.php:163)
        context.addGlobalActionLog("누군가가 <G><b>$destCityName</b></>$josaUl 살피는 것 같습니다.")

        // dist 분기별 로그 (che_첩보.php:164-196)
        if (dist <= 1) {
            context.addLog("<G><b>$destCityName</b></>의 정보를 많이 얻었습니다. <1>$date</>")
            context.addRawLog(cityBrief)
            context.addRawLog(cityDevel)

            // 병종 분포 (che_첩보.php:168-172)
            val crewTypeTexts = byCrewType.map { (crewTypeId, list) ->
                val unitName = opensamguk.common.constants.UnitCatalog.byId(crewTypeId)?.name ?: ""
                val crewTypeText = if (unitName.length >= 2) unitName.substring(0, 2) else unitName
                "$crewTypeText:${list.size}"
            }
            val crewTypeLine = if (crewTypeTexts.isNotEmpty()) crewTypeTexts.joinToString(" ") else ""
            context.addRawLog("【<S>병종</>】 $crewTypeLine")

            // 기술 비교 (che_첩보.php:174-188)
            val destNation = d.destNation
            val nation = d.nation
            if (destNation != null && nation != null && destNation.id != 0 && nation.id != 0) {
                val techDiff = kotlin.math.floor(destNation.tech) - kotlin.math.floor(nation.tech)
                val techText = when {
                    techDiff >= 1000 -> "<M>↑</>압도"
                    techDiff >= 250 -> "<Y>▲</>우위"
                    techDiff >= -250 -> "<W>↕</>대등"
                    techDiff >= -1000 -> "<G>▼</>열위"
                    else -> "<C>↓</>미미"
                }
                context.addLog("【<span class='ev_notice'>${destNation.name}</span>】아국대비기술:$techText")
            }
        } else if (dist == 2) {
            context.addLog("<G><b>$destCityName</b></>의 정보를 어느 정도 얻었습니다. <1>$date</>")
            context.addRawLog(cityBrief)
            context.addRawLog(cityDevel)
        } else {
            context.addLog("<G><b>$destCityName</b></>의 소문만 들을 수 있었습니다. <1>$date</>")
            context.addRawLog(cityBrief)
        }

        // nation spy 기록 (che_첩보.php:198-203)
        // PHP: $spyInfo[$destCityID] = 3; nation['spy'] = Json::encode($spyInfo)
        // Kotlin: nation.meta['spy']에 LinkedHashMap으로 저장
        val spyInfo = linkedMapOf<String, Any?>()
        spyInfo[destCityId.toString()] = 3
        d.nation = d.nation?.let { nation ->
            val existingSpy = (nation.meta["spy"] as? Map<String, Any?>) ?: emptyMap()
            val mergedSpy = LinkedHashMap(existingSpy)
            mergedSpy[destCityId.toString()] = 3
            nation.copy(meta = withMeta(nation.meta, "spy" to mergedSpy))
        }

        // RNG draws (che_첩보.php:205-206)
        val exp = rng.nextRangeInt(1, 100)    // DRAW1
        val ded = rng.nextRangeInt(1, 70)     // DRAW2

        val (reqGold, reqRice) = getCost(env)

        // cost 차감 + exp/ded + leadership_exp += 1 + lastTurn (che_첩보.php:208-215)
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
            meta = withMeta(g.meta, "leadership_exp" to metaDouble(g.meta, "leadership_exp") + 1),
            lastTurn = LastTurn(name, linkedMapOf("destCityID" to destCityId)),
        )
        d.general = g

        // PHP che_첩보.php:209 increaseInheritancePoint(active_action, 0.5) — P6 유산포인트 seam.
        // 골든 미캡처, 이 레이어에서 물리적 변화 없음. draw 추가 금지.

        StaticEventHandler.handleEvent(
            d.general,
            d.destGeneral,
            key,
            mapOf("year" to env.year, "startYear" to env.startYear, "develCost" to env.develCost),
            context.args,
        )
        val statChange = checkStatChange(d.general)
        d.general = statChange.general
        statChange.plainLogs.forEach { context.addPlainLog(it) }
    }
}

fun cheCheobo(pipeline: GeneralActionPipeline) = CheCheobo(pipeline)
