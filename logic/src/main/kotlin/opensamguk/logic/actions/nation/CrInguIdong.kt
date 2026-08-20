package opensamguk.logic.actions.nation

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.activeMapDestCity
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.nearCity
import opensamguk.logic.constraints.notSameDestCity
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.occupiedDestCity
import opensamguk.logic.constraints.reqCityCapacity
import opensamguk.logic.constraints.reqNationGold
import opensamguk.logic.constraints.reqNationRice
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.constraints.suppliedDestCity
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.clamp
import opensamguk.logic.util.phpRound
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.CalcCityDistance

/**
 * cr_인구이동 — `legacy/devsam-core/hwe/sammo/Command/Nation/cr_인구이동.php` (197줄) 충실 포팅.
 *
 * 국가 커맨드(NationCommand, cr_ 패밀리). 아국 src(액터 자신의 도시) 인구를 인접(NearCity(1))
 * 아국 dest 도시로 옮긴다. RNG 미사용 — deterministic(draw_count=0).
 *
 * argTest(cr_인구이동.php:31-66): `destCityID`(CityConst::byID 존재) + `amount`(is_numeric, int).
 *   amount > AMOUNT_LIMIT(100000) → AMOUNT_LIMIT로 절단, amount < 0 → reject. canonical `{destCityID, amount}`.
 *
 * 비용(getCost, cr_인구이동.php:113-118): `Util::round(develcost * arg.amount / 10000)`(half-away,
 *   [phpRound]) — gold == rice. **주의: getCost는 argTest-clamped amount(`$this->arg['amount']`)를 읽는다**
 *   (run()에서 도시 pop으로 재-clamp된 amount가 아니라). 따라서 비용은 "요청량"(argTest 절단값) 기준이고,
 *   실제 이동 인구는 src pop 한도로 추가 clamp된 값이다(cr_인구이동.php:158 vs :174).
 *
 * minConditionConstraints(cr_인구이동.php:77-82): [OccupiedCity, BeChief, SuppliedCity,
 *   ReqCityCapacity('pop','주민', minAvailableRecruitPop+100)].
 *
 * fullConditionConstraints(cr_인구이동.php:90-101), PHP ORDER:
 *   [NotSameDestCity, OccupiedCity, ReqCityCapacity('pop','주민', minAvailableRecruitPop+100),
 *    OccupiedDestCity, NearCity(1), BeChief, SuppliedCity, SuppliedDestCity,
 *    ReqNationGold(basegold + reqGold), ReqNationRice(baserice + reqRice)].
 *
 * run()(cr_인구이동.php:139-186):
 *   amount = clamp(arg.amount, null, src.pop - minAvailableRecruitPop)  // 하한 null, 상한만
 *   exp += 5, ded += 5 (리터럴 5 — nation 가변 magnitude 아님, cr_인구이동.php:164-165)
 *   dest.pop += amount; src.pop -= amount (cr_인구이동.php:167-172)
 *   nation.gold -= reqGold; nation.rice -= reqRice (reqGold/Rice = getCost(); cr_인구이동.php:174-178)
 *   로그 1줄(MONTH, 액터 본인 action; 전역 로그 없음, cr_인구이동.php:180):
 *     "<G><b>{dest}</b></>{josa-로} 인구 <C>{amount}</>명을 옮겼습니다. <1>{date}</>"
 *
 * getPreReqTurn/getPostReqTurn = 0 (cr_인구이동.php:120-128) → exp/ded는 [expDedMagnitude]를 쓰지
 * 않고 PHP가 명시한 리터럴 5를 그대로 부여한다.
 */
fun crInguIdong(pipeline: GeneralActionPipeline): CrInguIdong = CrInguIdong(pipeline)

class CrInguIdong(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "cr_인구이동"
    override val name: String get() = "인구이동"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destCityID" to "int", "amount" to "int")

    override fun getPreReqTurn(): Int = 0
    override fun getPostReqTurn(): Int = 0

    /** cr_인구이동.php:29 — `const AMOUNT_LIMIT = 100000;` (그냥!). */
    val amountLimit: Int get() = 100000

    /** ReqCityCapacity('pop','주민', minAvailableRecruitPop + 100)에 쓰이는 임계값. */
    private val reqPop: Int get() = GameConst.minAvailableRecruitPop + 100

    /**
     * cr_인구이동.php:113-118 getCost — `Util::round(develcost * amount / 10000)`(half-away).
     * gold == rice. amount는 argTest-clamped 요청량(`$this->arg['amount']`).
     */
    fun getCost(develCost: Int, amount: Int): Int =
        phpRound(develCost.toDouble() * amount / 10000.0)

    /**
     * cr_인구이동.php:31-66 argTest. destCityID(양의 정수; active-map FULL 검증) + amount(is_numeric → int,
     * AMOUNT_LIMIT 절단, 음수 reject). canonical `{destCityID, amount}` 또는 null.
     */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        val destCityID = (raw["destCityID"] as? Number)?.toInt() ?: return null
        if (destCityID <= 0) return null

        val rawAmount = raw["amount"] ?: return null
        var amount = when (rawAmount) {           // is_numeric → (int)
            is Number -> rawAmount.toInt()
            is String -> rawAmount.toDoubleOrNull()?.toInt() ?: return null
            else -> return null
        }
        if (amount > amountLimit) amount = amountLimit
        if (amount < 0) return null

        return linkedMapOf("destCityID" to destCityID, "amount" to amount)
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    /** cr_인구이동.php:77-82 minConditionConstraints. */
    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        occupiedCity(), beChief(), suppliedCity(),
        reqCityCapacity("pop", "주민", reqPop),
    )

    /** cr_인구이동.php:90-101 fullConditionConstraints (PHP ORDER 그대로). */
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val amount = (ctx.args["amount"] as? Number)?.toInt() ?: 0
        val cost = getCost((ctx.env["develCost"] as Number).toInt(), amount)
        val cityConstVariant = (ctx.env["mapName"] as? String)?.let(CityConstRegistry::find)
        val nearIds = cityConstVariant?.let { variant ->
            CalcCityDistance.nearCity(ctx.cityId ?: 0, 1, variant)
                .filterTo(LinkedHashSet()) { variant.byId(it) != null }
        } ?: emptySet()
        return listOf(
            activeMapDestCity(),
            notSameDestCity(),
            occupiedCity(),
            reqCityCapacity("pop", "주민", reqPop),
            occupiedDestCity(),
            nearCity(1) { _, _ -> nearIds },
            beChief(),
            suppliedCity(),
            suppliedDestCity(),
            reqNationGold { _, _ -> GameConst.basegold + cost },
            reqNationRice { _, _ -> GameConst.baserice + cost },
        )
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return
        val src = d.city
        val dest = d.destCity ?: return

        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: return
        val cityConstVariant = CityConstRegistry.of(context.env.mapName)
        val destCityName = cityConstVariant.byId(destCityId)?.name ?: cityConstVariant.byId(dest.id)?.name ?: ""
        val argAmount = (context.args["amount"] as? Number)?.toInt() ?: 0

        // cr_인구이동.php:158 — amount = clamp(arg.amount, null, src.pop - minAvailableRecruitPop).
        // 하한 null(=clamp 안 함), 상한만: src가 minAvailableRecruitPop 밑으로 내려가지 않도록.
        val amount = clamp(
            argAmount.toDouble(),
            min = null,
            max = (src.population - GameConst.minAvailableRecruitPop).toDouble(),
        ).toInt()

        // exp += 5, ded += 5 (리터럴 5 — cr_인구이동.php:164-165). 레벨 변동 시 PLAIN 라인.
        val expRes = addExperience(d.general, 5.0, pipeline)
        val dedRes = addDedication(expRes.general, 5.0, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // dest.pop += amount; src.pop -= amount (cr_인구이동.php:167-172).
        d.destCity = dest.copy(population = dest.population + amount)
        d.city = src.copy(population = src.population - amount)

        // nation gold/rice -= reqGold/reqRice. getCost는 argTest 요청량(argAmount) 기준 (cr_인구이동.php:174-178).
        val cost = getCost(context.env.develCost, argAmount)
        d.nation = nation.copy(gold = nation.gold - cost, rice = nation.rice - cost)

        // 로그 1줄(액터 본인 action, MONTH; 전역 로그 없음 — cr_인구이동.php:180).
        val josaRo = JosaUtil.pick(destCityName, "로")
        context.addLog("<G><b>$destCityName</b></>$josaRo 인구 <C>$amount</>명을 옮겼습니다. <1>${context.date}</>")
    }
}
