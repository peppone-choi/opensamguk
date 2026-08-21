package opensamguk.logic.actions.nation

import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.activeMapDestCity
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.occupiedDestCity
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import opensamguk.logic.world.CityConstRegistry
import kotlin.math.max

/**
 * che_백성동원 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_백성동원.php`.
 *
 * 전략(strategic) 사령 커맨드. 아국 점령 dest 도시의 수비(def)/성벽(wall)을 최대치 방향으로 끌어올린다.
 * RNG 미사용(deterministic). logLines=1.
 *
 * argTest(che_백성동원.php:31-48): `destCityID`(CityConst::byID 존재) 필요 → canonical `{destCityID}`.
 *
 * fullConditionConstraints(che_백성동원.php:67-72), PHP ORDER:
 *   [OccupiedCity, BeChief, OccupiedDestCity, AvailableStrategicCommand].
 *
 * run()(che_백성동원.php:121-179):
 *   1. actor 액션로그 `백성동원 발동! <1>{date}</>`.
 *   2. addExperience/addDedication += `5 * (getPreReqTurn()+1)` = 5 (preReqTurn=0).
 *   3. 같은 국가 다른 장수에게 PLAIN 브로드캐스트(타 장수 개인 로그 — golden broadcastLines=[]; addGlobalActionLog 아님).
 *   4. dest 도시: def = max(def_max*0.8, def), wall = max(wall_max*0.8, wall).
 *      SQL `def_max*0.8`(float→int 컬럼)을 phpRound(half-away)로 포팅.
 *   5. nation.strategic_cmd_limit = onCalcStrategic(globalDelay, 9) (golden snapshot 미캡처 → 미적용).
 */
fun cheBaekseongDongwon(pipeline: GeneralActionPipeline): CheBaekseongDongwon =
    CheBaekseongDongwon(pipeline)

class CheBaekseongDongwon(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_백성동원"
    override val name: String get() = "백성동원"
    override val category: String get() = "전략"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destCityID" to "int")

    override fun getPreReqTurn(): Int = 0

    /** Structural parser; active-map membership is enforced by the first FULL constraint. */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        val destCityID = (raw["destCityID"] as? Number)?.toInt() ?: return null
        if (destCityID <= 0) return null
        return linkedMapOf("destCityID" to destCityID)
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        occupiedCity(), beChief(), availableStrategicCommand(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        activeMapDestCity(), occupiedCity(), beChief(), occupiedDestCity(), availableStrategicCommand(),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: return

        // 1. actor 액션로그 (che_백성동원.php:142). MONTH prefix는 addLog 경계가 부착.
        context.addLog("백성동원 발동! <1>${context.date}</>")

        // 2. exp/ded += 5*(preReqTurn+1) = 5 (che_백성동원.php:144-145). pipeline fold(레벨변동 시 PLAIN).
        val expDed = 5.0 * (getPreReqTurn() + 1)
        val expRes = addExperience(d.general, expDed, pipeline)
        val dedRes = addDedication(expRes.general, expDed, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // 3. 같은 국가 다른 장수 PLAIN 브로드캐스트(che_백성동원.php:151-156) — 타 장수 개인 로그.
        //    golden broadcastLines=[] (addGlobalActionLog 아님). 같은 국가 candidate 장수에게 addPlainLogTo.
        if (context.candidateGenerals.isNotEmpty()) {
            val josaYi = opensamguk.common.josa.JosaUtil.pick(context.generalName, "이")
            val destCityName = CityConstRegistry.of(context.env.mapName).byId(destCityId)?.name ?: ""
            val broadcast =
                "<Y>${context.generalName}</>$josaYi <G><b>$destCityName</b></>에 <M>백성동원</>을 하였습니다."
            val nationId = d.nation?.id
            for (g in context.candidateGenerals) {
                if (g.id == d.general.id) continue
                if (nationId != null && g.nationId != nationId) continue
                context.addPlainLogTo(g.id, broadcast)
            }
        }

        // 4. dest 도시: def = max(def_max*0.8, def), wall = max(wall_max*0.8, wall) (che_백성동원.php:158-161).
        //    SQL float*0.8 → int 컬럼. phpRound(half-away)로 포팅.
        val destCity = if (d.destCity?.id == destCityId) d.destCity!!
            else if (d.city.id == destCityId) d.city
            else return
        val nextDef = max(phpRound(destCity.defenseMax * 0.8), destCity.defense)
        val nextWall = max(phpRound(destCity.wallMax * 0.8), destCity.wall)
        val raisedCity = destCity.copy(defense = nextDef, wall = nextWall)
        when {
            d.destCity?.id == destCityId -> d.destCity = raisedCity
            else -> d.city = raisedCity
        }

        // 5. nation.strategic_cmd_limit = onCalcStrategic(globalDelay, 9) (che_백성동원.php:166-168).
        //    golden snapshot에 미캡처(strategic_cmd_limit 컬럼 dump 없음) → 패러티 대상 외, 미적용.
    }
}
