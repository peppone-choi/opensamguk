package opensamguk.logic.war.hwiha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HwihaSiegeMoraleTest {
    @Test
    fun `완전 급식은 회복시키고 상한을 넘지 않는다`() {
        val fromLow = HwihaSiegeMorale.settle(1000, rationDemand = 50, rationServed = 50, encircled = true)
        assertEquals(2250, fromLow.morale)
        assertFalse(fromLow.surrendered)

        val atCap = HwihaSiegeMorale.settle(
            HwihaSiegeMorale.MAX_MORALE, rationDemand = 50, rationServed = 50, encircled = true,
        )
        assertEquals(HwihaSiegeMorale.MAX_MORALE, atCap.morale, "상한을 넘지 않는다")
    }

    @Test
    fun `군량을 전부 굶으면 25퍼센트를 잃는다`() {
        val out = HwihaSiegeMorale.settle(10000, rationDemand = 80, rationServed = 0, encircled = true)
        assertEquals(7500, out.morale)
    }

    @Test
    fun `부분 부족은 부족 비율만큼 올림으로 깎는다`() {
        // 부족 1/3 → ceil(2500 * 1 / 3) = 834. 내림이면 833 이 되어 사기를 공짜로 살려 준다.
        val out = HwihaSiegeMorale.settle(10000, rationDemand = 3, rationServed = 2, encircled = true)
        assertEquals(10000 - 834, out.morale)
    }

    @Test
    fun `사기 1 에서도 극소 부족이 항복까지 끌고 간다`() {
        // 정본 scope 가 명시한 경계: 올림이므로 아주 작은 부족도 최소 1 을 깎는다.
        val out = HwihaSiegeMorale.settle(1, rationDemand = 100_000, rationServed = 99_999, encircled = true)
        assertEquals(0, out.morale)
        assertTrue(out.surrendered)
    }

    @Test
    fun `같은 순 포위 해제는 사기 0 이어도 항복을 막는다`() {
        val relieved = HwihaSiegeMorale.settle(1000, rationDemand = 10, rationServed = 0, encircled = false)
        assertEquals(0, relieved.morale)
        assertFalse(relieved.surrendered, "구원이 제때 닿으면 항복하지 않는다")
    }

    @Test
    fun `이미 항복한 성은 다시 항복하지 않는다`() {
        val out = HwihaSiegeMorale.settle(
            0, rationDemand = 10, rationServed = 0, encircled = true, alreadySurrendered = true,
        )
        assertEquals(0, out.morale)
        assertFalse(out.surrendered, "항복은 최초 1회다 — 뒤 도착이 결과를 취소하지도 않는다")
    }

    @Test
    fun `수요가 없으면 굶길 것이 없어 완전 급식이다`() {
        val out = HwihaSiegeMorale.settle(5000, rationDemand = 0, rationServed = 0, encircled = true)
        assertEquals(6250, out.morale)
    }

    @Test
    fun `수요를 넘겨 먹여도 완전 급식으로만 센다`() {
        val out = HwihaSiegeMorale.settle(5000, rationDemand = 10, rationServed = 999, encircled = true)
        assertEquals(6250, out.morale)
    }

    @Test
    fun `정본 기준 시나리오 - 18순 군량이면 22순에 항복한다`() {
        // march-tempo-targets-v1.json: referenceInitialRationTurns 18, 기준 항복 22순.
        // 18순은 전부 먹고(사기 상한 유지), 그 뒤 완전 부족으로 2500 씩 네 순 → 22순에 0.
        var morale = HwihaSiegeMorale.INITIAL_MORALE
        var surrenderTurn: Int? = null
        for (turn in 1..40) {
            val fed = turn <= 18
            val out = HwihaSiegeMorale.settle(
                morale,
                rationDemand = 100,
                rationServed = if (fed) 100 else 0,
                encircled = true,
                alreadySurrendered = surrenderTurn != null,
            )
            morale = out.morale
            if (out.surrendered) surrenderTurn = turn
        }
        assertEquals(22, surrenderTurn, "정본 기준 초기 재고 18순 → 22순 항복")
        assertTrue(
            22 in 12..24,
            "정본 포위 목표 템포 12–24순 안에 든다",
        )
    }

    @Test
    fun `범위를 벗어난 입력은 거절한다`() {
        assertFailsWith<IllegalArgumentException> {
            HwihaSiegeMorale.settle(-1, rationDemand = 1, rationServed = 0, encircled = true)
        }
        assertFailsWith<IllegalArgumentException> {
            HwihaSiegeMorale.settle(HwihaSiegeMorale.MAX_MORALE + 1, rationDemand = 1, rationServed = 0, encircled = true)
        }
        assertFailsWith<IllegalArgumentException> {
            HwihaSiegeMorale.settle(100, rationDemand = -1, rationServed = 0, encircled = true)
        }
    }
}
