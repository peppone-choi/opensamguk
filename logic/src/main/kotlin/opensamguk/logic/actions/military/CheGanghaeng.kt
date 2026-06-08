package opensamguk.logic.actions.military

import opensamguk.common.constants.CityConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CalcCityDistance

/**
 * che_강행 — 강행(강행군). Faithful port of `che_강행.php` run() (lines 113-165).
 *
 * 일반 이동([CheIdong]) 대비:
 *   - 더 먼 거리 허용: NearCity(3) (이동은 NearCity(1)).
 *   - 추가 비용: cost = [develcost * 5, 0] (이동은 [develcost, 0]).
 *   - 추가 소모: train -= 5(floor 20) + atmos -= 5(floor 20) (이동은 atmos만).
 *   - 더 큰 경험치: exp = 100 (이동은 50).
 *   - 로그 동사 "강행" (이동은 "이동").
 *
 * run() 동작(php:131-162):
 *   logger.pushGeneralActionLog("<G><b>{dest}</b></>{josaRo} 강행했습니다. <1>{date}</>")
 *   setVar('city', destCityID)
 *   roaming-leader(officer_level==12 && nation.level==0): 같은 국가 모든 장수(no!=me)를 dest로 이동 +
 *     per-target PLAIN 로그 "방랑군 세력이 <G><b>{dest}</b></>{josaRo} 강행했습니다."
 *   increaseVarWithLimit('gold', -reqGold, 0)      → 하한 0 클램프
 *   increaseVarWithLimit('train', -5, 20)          → 하한 20 클램프
 *   increaseVarWithLimit('atmos', -5, 20)          → 하한 20 클램프
 *   addExperience(100); increaseVar('leadership_exp', 1)
 *
 * NearCity(3)는 F-MAP CalcCityDistance.nearCity 모듈을 소비(이동과 동일 시밍).
 * reqArg: arg['destCityID']. 턴 RNG draw 0(결정적). exportJSVars(cities/distanceList)는 read DTO.
 */
class CheGanghaeng(
    private val pipeline: GeneralActionPipeline,
) : GeneralActionDefinition {
    override val key = "che_강행"
    override val name = "강행"
    override val category = "인사"   // PHP availableGeneralCommand groups 강행/이동 under 인사
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destCityID" to "int")

    /** getCost(): [develcost * 5, 0] (php:77-81). 이동(develcost)의 5배 자금, 군량 0. */
    fun getCostGold(env: WorldEnv): Int = env.develCost * 5

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notSameDestCity(),                                                     // NotSameDestCity (php:54)
        nearCity(3) { c, _ -> CalcCityDistance.nearCity(c.cityId ?: 0, 3) },   // NearCity(3) (php:55)
        reqGeneralGold { c, _ -> getCostGold(envOf(c)) },                      // ReqGeneralGold(reqGold) (php:56)
        reqGeneralRice { _, _ -> 0 },                                          // ReqGeneralRice(reqRice=0) (php:57)
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val env = context.env
        val g0 = d.general

        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: d.destCity?.id
        ?: error("강행 requires a destCityID")
        val destCityName = d.destCity?.let { CityConst.byId(it.id)?.name } ?: CityConst.byId(destCityId)?.name
        ?: error("unknown dest city $destCityId")
        val josaRo = JosaUtil.pick(destCityName, "로")

        context.addLog("<G><b>$destCityName</b></>$josaRo 강행했습니다. <1>${context.date}</>")

        // roaming-leader: 같은 국가 모든 장수(no!=me)를 dest로 이동 + per-target PLAIN 로그(월 prefix 없음).
        if (g0.officerLevel == 12 && (d.nation?.level ?: 0) == 0) {
            for (follower in context.candidateGenerals) {
                if (follower.nationId != g0.nationId) continue
                if (follower.id == g0.id) continue
                d.cascadeGenerals.add(follower.copy(cityId = destCityId))
                // PHP $targetLogger->pushGeneralActionLog(text, PLAIN) → "<C>●</>{text}" (월 prefix 없음).
                context.addLogTo(follower.id, "<C>●</>방랑군 세력이 <G><b>$destCityName</b></>$josaRo 강행했습니다.")
            }
        }

        val reqGold = getCostGold(env)
        d.general = g0.copy(
            cityId = destCityId,                                 // setVar('city', destCityID)
            gold = maxOf(0, g0.gold - reqGold),                  // increaseVarWithLimit('gold', -reqGold, 0)
            train = ivwlFloor20(g0.train - 5.0),                 // increaseVarWithLimit('train', -5, 20) → floor 20
            atmos = ivwlFloor20(g0.atmos - 5.0),                 // increaseVarWithLimit('atmos', -5, 20) → floor 20
            experience = g0.experience + 100.0,                  // addExperience(100)
            meta = withMeta(g0.meta, "leadership_exp" to metaDouble(g0.meta, "leadership_exp") + 1),
            lastTurn = LastTurn(name, arg = linkedMapOf<String, Any?>("destCityID" to destCityId)),
        )
    }

    /** increaseVarWithLimit(key, -5, min 20): 하한 20 클램프(상한 없음). */
    private fun ivwlFloor20(value: Double): Double = if (value < 20.0) 20.0 else value

    private fun envOf(c: ConstraintContext) = WorldEnv(
        year = (c.env["year"] as Number).toInt(),
        startYear = (c.env["startYear"] as Number).toInt(),
        develCost = (c.env["develCost"] as Number).toInt(),
    )
}
