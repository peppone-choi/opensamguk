package opensamguk.engine.v2

import opensamguk.logic.domain.City
import opensamguk.logic.domestic.getGoldIncome
import opensamguk.logic.domestic.getRiceIncome
import opensamguk.logic.domestic.getWallIncome
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.IncomeGeneral
import opensamguk.logic.world.IncomeNation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPENSAM-151 (v2 R2) — [v2ProcessCityIncome] 계약 테스트.
 *
 * PHP 오라클이 없다(v2 도시 원장은 오픈삼국 독자 설계). 그래서 이 테스트가 고정하는 것은 "PHP와 같다"가
 * 아니라 세 가지다:
 *   ① 국가 총수입은 v1 `getGoldIncome`/`getRiceIncome+getWallIncome`과 같은 값이어야 한다(재조립이
 *      수식을 바꾸지 않았다는 증거). 결합 순서가 달라 ulp 차이가 날 수 있으므로 상대오차로 본다.
 *   ② 도시 하한은 0이다 — v1 `GameConst.baserice`(2000)를 도시마다 적용하면 안 된다.
 *   ③ 봉록은 장수의 소속 도시 원장에서 나가고, 타국 도시에 있는 장수는 수도로 fallback한다.
 */
class V2ProcessCityIncomeTest {

    private val pipeline = GeneralActionPipeline(emptyList())

    private fun city(id: Int, pop: Int = 30000, trust: Double = 100.0) = City(
        id = id, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 2000, agriculture = 1000, agricultureMax = 2000,
        supplyState = 1, frontState = 0, trust = trust,
        security = 1000, securityMax = 2000, defense = 1000, wall = 1000, wallMax = 2000,
        population = pop, populationMax = 100000,
    )

    private fun gen(id: Int, dedication: Double = 1000.0, officerLevel: Int = 5) =
        IncomeGeneral(id = id, dedication = dedication, officerLevel = officerLevel)

    private fun nation(
        id: Int = 1,
        cities: List<City> = listOf(city(1), city(2)),
        generals: List<IncomeGeneral> = listOf(gen(1), gen(2)),
        capitalId: Int = 1,
        taxRate: Double = 20.0,
        bill: Double = 100.0,
    ) = IncomeNation(
        id = id, name = "촉", gold = 10000, rice = 10000, level = 5,
        taxRate = taxRate, bill = bill, capitalId = capitalId, nationType = null,
        cities = cities, generals = generals,
    )

    private fun entry(
        n: IncomeNation = nation(),
        generalCityIds: Map<Int, Int> = mapOf(1 to 1, 2 to 2),
        ledger: Map<Int, Long> = mapOf(1 to 0L, 2 to 0L),
    ) = V2CityIncomeNation(n, generalCityIds, ledger)

    /** ① 재조립이 v1 수식을 바꾸지 않았다. */
    @Test
    fun `nation gross income matches the v1 aggregate`() {
        val n = nation(taxRate = 20.01, generals = emptyList())

        val goldExpected = getGoldIncome(n.cities, n.capitalId, n.level, n.taxRate, n.nationType, pipeline)
        val gold = v2ProcessCityIncome("gold", listOf(entry(n, emptyMap())), pipeline)
        assertRelativelyEqual(goldExpected, gold.prevIncome[1]!!)

        val riceExpected = getRiceIncome(n.cities, n.capitalId, n.level, n.taxRate, n.nationType, pipeline) +
            getWallIncome(n.cities, n.capitalId, n.level, n.taxRate, n.nationType, pipeline)
        val rice = v2ProcessCityIncome("rice", listOf(entry(n, emptyMap())), pipeline)
        assertRelativelyEqual(riceExpected, rice.prevIncome[1]!!)
    }

    /** 도시 id 오름차순으로 원장 델타가 나온다(결정론 고정). */
    @Test
    fun `ledger deltas are emitted per city in ascending city id`() {
        val n = nation(cities = listOf(city(3), city(1), city(2)), generals = emptyList())
        val result = v2ProcessCityIncome("gold", listOf(entry(n, emptyMap(), mapOf(1 to 0L, 2 to 0L, 3 to 0L))), pipeline)
        assertEquals(listOf(1, 2, 3), result.ledgerDeltas.map { it.cityId })
        assertTrue(result.ledgerDeltas.all { it.delta > 0 }, "봉록 대상이 없으면 수입이 통째로 원장에 남는다")
    }

    /** ② 도시 하한은 0 — 쌀이라고 도시마다 baserice(2000)를 깔지 않는다. */
    @Test
    fun `city floor is zero for rice, not GameConst baserice`() {
        // 봉록 총액이 (잔액+수입)보다 훨씬 크면 원장은 0까지 내려가고 음수로는 안 간다.
        // v1 하한(baserice=2000)이 도시에 걸려 있었다면 잔액 5000은 2000에서 멈췄을 것이다.
        val before = 5000L
        val n = nation(cities = listOf(city(1)), generals = listOf(gen(1, dedication = 1_000_000.0)), bill = 100.0)
        val result = v2ProcessCityIncome("rice", listOf(entry(n, mapOf(1 to 1), mapOf(1 to before))), pipeline)
        val delta = result.ledgerDeltas.single { it.cityId == 1 }.delta
        assertEquals(0L, before + delta, "지출이 수입보다 커도 원장은 0에서 멈춘다(하한 2000이 아니다)")
        assertTrue(result.generalPayouts.single().amount > 0, "0까지 긁어 준 만큼은 봉록으로 나간다")
    }

    /** ③ 봉록은 소속 도시에서 나간다 — 도시별로 ratio가 다르면 지급액도 달라진다. */
    @Test
    fun `salary is paid out of the general's home city ledger`() {
        // 인구가 다른 두 도시에 장수를 하나씩 두고, 잔액이 0인 쪽만 지급이 깎이게 만든다.
        val poor = city(1, pop = 300)
        val rich = city(2, pop = 300000)
        val n = nation(cities = listOf(poor, rich), generals = listOf(gen(1), gen(2)), capitalId = 2)
        val result = v2ProcessCityIncome("gold", listOf(entry(n, mapOf(1 to 1, 2 to 2))), pipeline)

        val payOfPoorCityGeneral = result.generalPayouts.single { it.generalId == 1 }.amount
        val payOfRichCityGeneral = result.generalPayouts.single { it.generalId == 2 }.amount
        assertTrue(
            payOfPoorCityGeneral < payOfRichCityGeneral,
            "가난한 도시 소속 장수는 그 도시 원장만큼만 받는다: $payOfPoorCityGeneral vs $payOfRichCityGeneral",
        )
    }

    /** 타국 도시에 있는 장수는 수도 원장에서 받는다(귀속처 fallback). */
    @Test
    fun `general outside the nation's cities falls back to the capital ledger`() {
        val n = nation(cities = listOf(city(1)), generals = listOf(gen(7)), capitalId = 1)
        val result = v2ProcessCityIncome("gold", listOf(entry(n, mapOf(7 to 999), mapOf(1 to 0L))), pipeline)
        assertEquals(1, result.generalPayouts.size)
        assertTrue(result.generalPayouts.single().amount > 0)
    }

    /** 도시가 없는 국가는 봉록도 원장 델타도 없다 — 유령 지급을 만들지 않는다. */
    @Test
    fun `nation with no cities pays nobody`() {
        val n = nation(cities = emptyList(), generals = listOf(gen(1)), capitalId = 0)
        val result = v2ProcessCityIncome("gold", listOf(entry(n, mapOf(1 to 5), emptyMap())), pipeline)
        assertTrue(result.ledgerDeltas.isEmpty())
        assertTrue(result.generalPayouts.isEmpty())
        assertEquals(0.0, result.prevIncome[1])
    }

    /** 국가 id 오름차순 처리. */
    @Test
    fun `nations processed in ascending id order`() {
        val a = entry(nation(id = 2, cities = listOf(city(10)), generals = emptyList()), emptyMap(), mapOf(10 to 0L))
        val b = entry(nation(id = 1, cities = listOf(city(20)), generals = emptyList()), emptyMap(), mapOf(20 to 0L))
        val result = v2ProcessCityIncome("gold", listOf(a, b), pipeline)
        assertEquals(listOf(1, 2), result.prevIncome.keys.toList())
        assertEquals(listOf(20, 10), result.ledgerDeltas.map { it.cityId })
    }

    /** RNG를 쓰지 않는다 — 같은 입력이면 몇 번을 돌려도 같은 결과. */
    @Test
    fun `is deterministic (no RNG draws)`() {
        val input = listOf(entry())
        assertEquals(
            v2ProcessCityIncome("gold", input, pipeline),
            v2ProcessCityIncome("gold", input, pipeline),
        )
    }

    private fun assertRelativelyEqual(expected: Double, actual: Double) {
        val tolerance = abs(expected) * 1e-12
        assertTrue(
            abs(expected - actual) <= tolerance,
            "기대 $expected, 실제 $actual (허용 $tolerance) — 결합 순서 차이(ulp)를 넘어선 수식 드리프트",
        )
    }
}
