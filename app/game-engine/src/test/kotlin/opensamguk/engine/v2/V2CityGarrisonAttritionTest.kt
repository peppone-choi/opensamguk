package opensamguk.engine.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPENSAM-152 (v2 R3) — 도시병사 감소·공백지화 순수 판정 테스트.
 *
 * RNG 를 쓰지 않는다. `attritionLoss`/`v2CityGarrisonAttrition` 어느 쪽도 `RandUtil` 을 인자로 받지
 * 않으므로 draw 0 은 타입 수준에서 이미 참이고, 여기서 고정하는 것은 **판정 절차**(§2.4)다.
 */
class V2CityGarrisonAttritionTest {

    private fun city(id: Int, state: Int, garrison: Int, nationId: Int = 1) =
        V2AttritionCity(cityId = id, name = "성$id", nationId = nationId, state = state, garrison = garrison)

    // ── attritionLoss (순수 함수) ──────────────────────────────────────────────────────────────

    @Test
    fun `garrison zero loses nothing`() {
        assertEquals(0, attritionLoss(0, 300))
        assertEquals(0, attritionLoss(-5, 300))
    }

    /**
     * 묘섭 원문 값(`help__start__other__etcetera.md:67`) — 장수 300명 기준 감소량은 **3000명**이다.
     * OPENSAM-193 교정 전에는 여기가 지어낸 정률(garrison × 12.5%)이었다.
     */
    @Test
    fun `at the 300-general reference the loss is the myosam base of 3000`() {
        assertEquals(3000, attritionLoss(10_000, 300))
        // 기준선을 넘겨도 더 아프지 않다(coerce 상한).
        assertEquals(3000, attritionLoss(10_000, 5_000))
        // garrison 에 비례하지 않는다 — 병력이 두 배여도 감소량은 같다.
        assertEquals(3000, attritionLoss(20_000, 300))
    }

    /** 묘섭 원문 값 — 장수가 0이면 500명, 즉 3000의 정확히 1/6 이다(같은 줄). */
    @Test
    fun `with no generals the loss floors at the myosam 500`() {
        assertEquals(500, attritionLoss(10_000, 0))
        assertEquals(ATTRITION_BASE_LOSS / 6, attritionLoss(10_000, 0))
    }

    /** 300명 사이 보간은 선형 — 묘섭 미명시, 오픈삼국 결정. */
    @Test
    fun `interpolation between zero and 300 generals is linear`() {
        val full = attritionLoss(10_000, 300).toDouble()
        val none = attritionLoss(10_000, 0).toDouble()
        val half = attritionLoss(10_000, 150).toDouble()
        assertTrue(kotlin.math.abs(half - (none + full) / 2) <= 1.0, "half=$half none=$none full=$full")
    }

    /**
     * 정률이라면 `garrison` 이 0에 **도달하지 못해** 공백지화가 영영 안 일어난다.
     * 절대 수량 + `garrison` 절단이 그 종결성을 만든다.
     */
    @Test
    fun `a small garrison is wiped out by the flat base, so vacating is reachable`() {
        assertEquals(50, attritionLoss(50, 300))
        assertEquals(1, attritionLoss(1, 300))
        assertTrue(attritionLoss(1, 0) >= 1, "장수가 없어도 감소는 최소 1명 이상이어야 종결한다")
    }

    @Test
    fun `loss never exceeds the garrison`() {
        for (g in listOf(1, 7, 99, 100, 101, 1000)) {
            for (n in listOf(0, 1, 42, 300, 900)) {
                assertTrue(attritionLoss(g, n) <= g, "garrison=$g generals=$n")
            }
        }
    }

    // ── 판정 절차 (§2.4) ───────────────────────────────────────────────────────────────────────

    @Test
    fun `months outside 1-4-7-10 do nothing at all`() {
        val cities = listOf(city(1, state = 5, garrison = 10_000))
        for (m in listOf(2, 3, 5, 6, 8, 9, 11, 12)) {
            assertTrue(v2CityGarrisonAttrition(m, cities, 300).outcomes.isEmpty(), "month $m")
        }
        assertTrue(v2CityGarrisonAttrition(1, cities, 300).outcomes.isNotEmpty())
    }

    /** 재난 코드 3~9 만 대상 — 호황 2·풍작 1·재난없음 0 은 제외(`RaiseDisaster.kt:104-133`). */
    @Test
    fun `only disaster state codes 3 to 9 are touched`() {
        val cities = (0..10).map { city(id = it, state = it, garrison = 10_000) }
        val hit = v2CityGarrisonAttrition(1, cities, 300).outcomes.map { it.cityId }
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9), hit)
        assertEquals(BAD_STATE_CODES, hit.toSet())
    }

    /** 순회 순서는 결과의 일부 — 입력이 뒤섞여도 출력은 준 순서를 그대로 따른다. */
    @Test
    fun `outcomes follow the supplied city order`() {
        val cities = listOf(city(7, 5, 10_000), city(2, 5, 10_000), city(9, 5, 10_000))
        assertEquals(listOf(7, 2, 9), v2CityGarrisonAttrition(4, cities, 300).outcomes.map { it.cityId })
    }

    @Test
    fun `garrison is reduced and never goes below zero`() {
        val o = v2CityGarrisonAttrition(7, listOf(city(1, 5, 10_000)), 300).outcomes.single()
        assertEquals(10_000, o.before)
        assertEquals(7_000, o.after) // 10000 - 3000(묘섭 기준 감소량)
        assertFalse(o.vacated)

        val wiped = v2CityGarrisonAttrition(7, listOf(city(1, 5, 30)), 300).outcomes.single()
        assertEquals(0, wiped.after)
    }

    /** 공백지화는 감소 **직후 같은 반복 안에서** 결정된다 — 별도 스캔이 아니다. */
    @Test
    fun `hitting zero vacates the city in the same iteration`() {
        val o = v2CityGarrisonAttrition(10, listOf(city(1, 9, 30, nationId = 4)), 300).outcomes.single()
        assertEquals(0, o.after)
        assertTrue(o.vacated)
        assertTrue(o.logLines.any { "30" in it && "공백지" in it }, o.logLines.toString())
    }

    /** 이미 공백지(nationId=0)인 도시는 다시 공백지화되지 않는다. */
    @Test
    fun `an already-neutral city is not vacated again`() {
        val o = v2CityGarrisonAttrition(1, listOf(city(1, 9, 30, nationId = 0)), 300).outcomes.single()
        assertEquals(0, o.after)
        assertFalse(o.vacated)
    }

    /**
     * 묘섭 원문의 "도시병사가 **없거나**"(§2.4 인용) — 이미 0인 도시도 재난을 맞으면 공백지가 된다.
     * 감소량이 0이라 원장 델타는 없지만 소유권 전이는 일어난다.
     */
    @Test
    fun `a city that already has zero garrison is vacated on disaster`() {
        val o = v2CityGarrisonAttrition(1, listOf(city(1, 3, 0, nationId = 2)), 300).outcomes.single()
        assertEquals(0, o.before)
        assertEquals(0, o.after)
        assertTrue(o.vacated)
    }

    /** 변화도 공백지화도 없으면 결과 행 자체가 없다(불필요한 flush 델타 금지). */
    @Test
    fun `a neutral zero-garrison city produces no outcome row`() {
        assertTrue(v2CityGarrisonAttrition(1, listOf(city(1, 3, 0, nationId = 0)), 300).outcomes.isEmpty())
    }

    @Test
    fun `the same input always yields the same result - no rng anywhere`() {
        val cities = (1..20).map { city(it, state = it % 11, garrison = it * 137) }
        val first = v2CityGarrisonAttrition(4, cities, 77)
        repeat(5) { assertEquals(first, v2CityGarrisonAttrition(4, cities, 77)) }
    }
}
