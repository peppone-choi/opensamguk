package opensamguk.engine.v2

import kotlinx.serialization.json.JsonPrimitive
import opensamguk.logic.domestic.calcCityGoldIncome
import opensamguk.logic.domestic.calcCityRiceIncome
import opensamguk.logic.domestic.calcCityWallRiceIncome
import opensamguk.logic.domestic.getBill
import opensamguk.logic.domestic.getOutcome
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.log.HistoryTokens
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit
import opensamguk.logic.world.IncomeGeneralPayout
import opensamguk.logic.world.IncomeNation

/**
 * OPENSAM-151 (v2 R2) — `V2ProcessCityIncome`: the per-city reassembly of the semi-annual income tick.
 *
 * **이것은 패러티 포트가 아니다.** v1 `ProcessIncome`(`logic/world/ProcessIncome.kt`)은 국가 단위로 수입을
 * 걷어 `nation.gold`/`nation.rice`에 넣는다. v2는 금·쌀·수비병을 **도시가 소유**하므로
 * (`docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md` §2.2), 같은 계산을 도시 단위로
 * 재조립한다. PHP 오라클이 없다 — 아래 세 가지는 **선언된 divergence**이지 포팅한 값이 아니다:
 *
 *  1. **도시별 `base` = 0 (금·쌀 둘 다).** v1의 `GameConst.baserice`(2000)는 *국가* 하한선이라
 *     도시마다 적용하면 도시 수만큼 곱해진 하한이 된다. v2는 국고(`nation.gold`)가 별도 계정이므로
 *     도시 원장에는 하한을 두지 않는다.
 *  2. **봉록 귀속 도시 = 장수의 소속 도시**([V2CityIncomeNation.generalCityIds]). 그 도시가 이 국가의
 *     도시가 아니면(타국 도시에 있는 장수) 수도로 되돌린다. 국가가 도시를 하나도 안 가지면 봉록 없음.
 *  3. **잔차(residual) 규칙 없음.** 도시별 3분기 정산이 각자 끝나고, 못 준 봉록을 다른 도시가 대신
 *     메우지 않는다. v1의 국가 단일 계정에서는 존재할 수 없던 상황이라 대응하는 PHP 동작이 없다.
 *
 * divergence가 아닌 것(= v1과 수치가 같아야 하는 것):
 *  - 도시별 수입 공식은 v1과 **같은 함수**를 부른다(`calcCity{Gold,Rice,WallRice}Income`). 세율 스케일
 *    `* (taxRate/20)`을 도시별로 걸되 Double을 유지한다 — 도시별로 반올림하면 국가 합계가 v1에서
 *    실수 단위로 벌어진다. 반올림 지점은 원장 기록 1회뿐이다.
 *    다만 v1은 `(Σ cityInt_i) * s`로 한 번 곱하고 v2는 `Σ (cityInt_i * s)`로 나눠 곱하므로,
 *    **실수 산술로는 같지만 IEEE754에서는 마지막 자리(ulp) 차이가 날 수 있다.** 정수 원장에 들어갈 땐
 *    어차피 반올림되므로 실질 차이는 없고, 테스트도 그래서 상대오차로 대조한다(비트 동일 주장 금지).
 *  - 3분기 정산 분기 형태·`getOutcome`·`getBill(dedication) * ratio`·로그 토큰은 v1 그대로.
 *  - RNG draw 0개. 이 leaf는 난수를 뽑지 않는다(v1 `ProcessIncome`도 같다).
 */

/** 한 도시의 원장 델타. [V2CityLedgerStore.adjust]가 소비하는 증분(절대값 아님). */
data class V2CityLedgerDelta(val cityId: Int, val delta: Long)

/**
 * 한 국가의 도시 수입 입력. v1 [IncomeNation]을 **그대로 재사용**하고(도시·장수·세율·bill·수도·국가타입
 * 전부 이미 들어 있다) v2가 추가로 필요한 두 가지만 덧붙인다.
 *
 * @param generalCityIds 장수 id → 소속 도시 id (봉록 귀속처).
 * @param ledger 도시 id → 해당 자원의 현재 원장 잔액.
 */
data class V2CityIncomeNation(
    val nation: IncomeNation,
    val generalCityIds: Map<Int, Int>,
    val ledger: Map<Int, Long>,
)

/** 적용 대상 델타 묶음. 국가 자원은 건드리지 않는다 — v2에서 수입은 도시 원장으로만 들어간다. */
data class V2CityIncomeResult(
    val resource: String,
    val ledgerDeltas: List<V2CityLedgerDelta>,
    val prevIncome: Map<Int, Double>,
    val generalPayouts: List<IncomeGeneralPayout>,
    val globalHistory: String,
)

/**
 * 도시 단위 수입 정산. 국가 id 오름차순 → 도시 id 오름차순(결정론 고정, v1의 `SELECT` 행 순서 관례와 동형).
 *
 * `prevIncome`은 **국가 단위로 유지**한다(설계 §2.3 판정 (a)) — 값은 그 국가 도시들의 수입 합.
 * `nation_env.prev_income_{gold,rice}` KV 소비처(랭킹·내정 화면)가 국가 단위라서 도시별로 쪼개면
 * 소비처가 전부 깨진다.
 */
fun v2ProcessCityIncome(
    resource: String,
    nations: List<V2CityIncomeNation>,
    pipeline: GeneralActionPipeline,
): V2CityIncomeResult {
    require(resource == "gold" || resource == "rice") { "잘못된 자원 타입" }
    val isGold = resource == "gold"

    val ledgerDeltas = ArrayList<V2CityLedgerDelta>()
    val prevIncome = LinkedHashMap<Int, Double>()
    val generalPayouts = ArrayList<IncomeGeneralPayout>()

    for (entry in nations.sortedBy { it.nation.id }) {
        val nation = entry.nation
        val cities = nation.cities.sortedBy { it.id }
        val cityIds = cities.map { it.id }.toSet()
        // 봉록 귀속: 소속 도시가 이 국가 도시가 아니면 수도로 fallback. 수도조차 없으면 그 장수는
        // 이번 정산에서 빠진다(지급할 원장이 없다) — 빈 국가에 유령 봉록을 만들지 않는다.
        val billersByCity = nation.generals.groupBy { g ->
            val home = entry.generalCityIds[g.id]
            if (home != null && home in cityIds) home else nation.capitalId.takeIf { it in cityIds }
        }

        var nationIncome = 0.0
        for (city in cities) {
            val officerCnt = nation.officerCntByCity[city.id] ?: 0
            val isCapital = nation.capitalId == city.id
            val cityUnits = if (isGold) {
                calcCityGoldIncome(city, officerCnt, isCapital, nation.level, nation.nationType, pipeline)
            } else {
                calcCityRiceIncome(city, officerCnt, isCapital, nation.level, nation.nationType, pipeline) +
                    calcCityWallRiceIncome(city, officerCnt, isCapital, nation.level, nation.nationType, pipeline)
            }
            // 도시별로 반올림하지 않는다 — Double을 유지해야 국가 합계가 v1과 정확히 일치한다.
            val income = cityUnits * (nation.taxRate / 20)
            nationIncome += income

            val billers = billersByCity[city.id].orEmpty()
            val originOutcome = getOutcome(100.0, billers.map { it.dedication })
            val outcome = phpRound(nation.bill / 100.0 * originOutcome)

            // divergence ①: 도시 원장 하한은 0이다(v1의 GameConst.basegold/baserice 아님).
            val base = 0.0
            val before = entry.ledger[city.id] ?: 0L
            var res = before.toDouble() + income
            val realOutcome: Double
            val ratio: Double
            if (res < base) {
                realOutcome = 0.0
                ratio = 0.0
            } else if (res - base < outcome) {
                realOutcome = res - base
                res = base
                ratio = if (originOutcome == 0) 0.0 else realOutcome / originOutcome
            } else {
                realOutcome = outcome.toDouble()
                res -= realOutcome
                ratio = if (originOutcome == 0) 0.0 else realOutcome / originOutcome
            }
            res = valueFit(res, base)

            val delta = phpRound(res).toLong() - before
            if (delta != 0L) ledgerDeltas.add(V2CityLedgerDelta(city.id, delta))

            // 수입 표시줄은 v1 토큰 그대로 쓰되 값이 **그 도시의** 수입이다(v2에서 수입은 도시가 번다).
            val cityIncomeRounded = phpRound(income)
            val incomeLine =
                if (isGold) HistoryTokens.goldIncomeLine(cityIncomeRounded) else HistoryTokens.riceIncomeLine(cityIncomeRounded)
            for (g in billers) {
                val pay = phpRound(getBill(g.dedication) * ratio)
                val lines = ArrayList<String>(2)
                if (g.officerLevel > 4) lines.add(incomeLine)
                lines.add(if (isGold) HistoryTokens.goldSalaryLine(pay) else HistoryTokens.riceSalaryLine(pay))
                generalPayouts.add(IncomeGeneralPayout(g.id, pay, lines))
            }
        }
        prevIncome[nation.id] = nationIncome
    }

    val globalHistory = if (isGold) HistoryTokens.springIncomeGlobal() else HistoryTokens.autumnIncomeGlobal()
    return V2CityIncomeResult(resource, ledgerDeltas, prevIncome, generalPayouts, globalHistory)
}

/**
 * v2 시나리오의 `event` 행이 이름으로 부르는 leaf. v1 `ProcessIncome`과 **동시에 등록되지만**, v2 월드는
 * `ignoreDefaultEvents: true` + 자체 event 행으로 1월/7월 자리에 이 leaf를 넣는다 — 두 leaf가 같은 월드에서
 * 같이 돌면 수입이 두 번 걷힌다.
 */
class V2ProcessCityIncomeAction(val resource: String) : EventAction {
    init {
        require(resource == "gold" || resource == "rice") { "잘못된 자원 타입" }
    }

    override fun run(ctx: EventActionContext) {
        // fail-closed: v2 컨텍스트(도시 원장)가 없으면 조용한 no-op이 아니라 죽는다. 무음 no-op이면
        // 수입이 통째로 사라진 월드가 그린으로 보인다.
        val vc = ctx as? V2CityIncomeContext
            ?: error("V2ProcessCityIncomeAction requires a V2CityIncomeContext (v2 city ledger unavailable)")
        vc.applyV2CityIncome(v2ProcessCityIncome(resource, vc.v2CityIncomeNations(resource), vc.pipeline))
    }

    companion object {
        const val NAME = "V2ProcessCityIncome"

        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args ->
                V2ProcessCityIncomeAction((args[0] as JsonPrimitive).content)
            }
    }
}

/** [V2ProcessCityIncomeAction]이 요구하는 디스패치 컨텍스트. 데몬이 공급한다. */
interface V2CityIncomeContext : EventActionContext {
    val pipeline: GeneralActionPipeline
    /** [resource]는 "gold"|"rice" — [V2CityIncomeNation.ledger]가 어느 원장 칸을 담을지 결정한다. */
    fun v2CityIncomeNations(resource: String): List<V2CityIncomeNation>
    fun applyV2CityIncome(result: V2CityIncomeResult)
}
