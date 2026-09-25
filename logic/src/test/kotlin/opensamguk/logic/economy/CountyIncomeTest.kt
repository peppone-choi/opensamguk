package opensamguk.logic.economy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HwihaCountyIncomeTest {
    private fun state(
        owner: Int = 1, population: Int = 1_000, commerce: Int = 100, commerceMax: Int = 100,
        agriculture: Int = 100, agricultureMax: Int = 100, supplied: Boolean = true,
    ) = HwihaCountyIncome.CountyState(owner, population, commerce, commerceMax, agriculture, agricultureMax, supplied)

    @Test
    fun `호수는 인구를 5로 절삭한다`() {
        assertEquals(0, HwihaCountyIncome.households(4))
        assertEquals(1, HwihaCountyIncome.households(5))
        assertEquals(1, HwihaCountyIncome.households(9))
        assertEquals(200, HwihaCountyIncome.households(1_000))
    }

    @Test
    fun `완전개발은 호당 전 20 곡 200 이다`() {
        val produced = HwihaCountyIncome.monthly(state(population = 1_000))
        assertEquals(HwihaResources(money = 200 * 20, grain = 200 * 200), produced)
    }

    @Test
    fun `개발이 상한의 절반이면 생산도 절반이다`() {
        val produced = HwihaCountyIncome.monthly(state(population = 1_000, commerce = 50, agriculture = 50))
        assertEquals(HwihaResources(money = 2_000, grain = 20_000), produced)
    }

    @Test
    fun `개발이 상한을 넘어도 상한까지만 센다`() {
        val produced = HwihaCountyIncome.monthly(state(population = 1_000, commerce = 400, agriculture = 400))
        assertEquals(HwihaCountyIncome.monthly(state(population = 1_000)), produced)
    }

    @Test
    fun `상한이 0 이면 그 축은 0 이고 0 으로 나누지 않는다`() {
        val produced = HwihaCountyIncome.monthly(state(population = 1_000, commerce = 0, commerceMax = 0))
        assertEquals(HwihaResources(money = 0, grain = 200 * 200), produced)
    }

    @Test
    fun `절삭은 버린다 — 나머지를 올리지 않는다`() {
        // 호수 1 · 개발 1/3 → 전 20*1/3 = 6, 곡 200*1/3 = 66.
        val produced = HwihaCountyIncome.monthly(
            state(population = 5, commerce = 1, commerceMax = 3, agriculture = 1, agricultureMax = 3)
        )
        assertEquals(HwihaResources(money = 6, grain = 66), produced)
    }

    @Test
    fun `보급이 끊긴 縣 은 생산하지 않는다 — 산지도 같이 멈춘다`() {
        val produced = HwihaCountyIncome.monthly(state(supplied = false), HwihaResources(iron = 7))
        assertEquals(HwihaResources(), produced)
    }

    @Test
    fun `무주 縣 은 생산하지 않는다 — 점령 횡재를 만들지 않는다`() {
        assertEquals(HwihaResources(), HwihaCountyIncome.monthly(state(owner = 0), HwihaResources(iron = 7)))
        assertEquals(HwihaResources(), HwihaCountyIncome.monthly(state(owner = -1)))
    }

    @Test
    fun `산지 생산은 전 곡 위에 더해진다`() {
        val produced = HwihaCountyIncome.monthly(
            state(population = 1_000), HwihaResources(iron = 3, timber = 5, horses = 2)
        )
        assertEquals(HwihaResources(money = 4_000, grain = 40_000, iron = 3, timber = 5, horses = 2), produced)
    }

    @Test
    fun `호수가 0 이면 산지 생산만 남는다`() {
        val produced = HwihaCountyIncome.monthly(state(population = 4), HwihaResources(iron = 3))
        assertEquals(HwihaResources(iron = 3), produced)
    }

    @Test
    fun `음수 입력은 거절한다`() {
        assertFailsWith<IllegalArgumentException> { state(population = -1) }
        assertFailsWith<IllegalArgumentException> { state(commerceMax = -1) }
        assertFailsWith<IllegalArgumentException> { HwihaCountyIncome.households(-1) }
    }
}
