package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Q14 `checkEmperior` 천하통일 탐지 단위 테스트 — PHP `func_gamerule.php:696-769` 충실 검증.
 * no-rng, Docker 불요. 격리된 1회성 부수효과(checkStatistic/경매/상속/refreshLimit/CheckHall)는
 * 본 바퀴 범위 밖(Phase 4 백로그) — 여기선 detection + isunited 전이 + 전토통일 로그만 검증한다.
 * 단언 문자열은 checkEmperior 가 emit 하는 inner-text 다. PHP store 시점의 YEAR_MONTH 접두
 * (`<C>●</>{year}년 {month}월:`)는 선존재 엔진-광역 로그포맷 갭(LEDGER 백로그)이라 본 테스트 범위 밖.
 */
class CheckEmperiorTest {

    /** 캡처용 fake 컨텍스트. */
    private class FakeCtx(
        private val isunitedValue: Int,
        private val active: List<Int>,
        private val cityCounts: Map<Int, Int>,
        private val total: Int,
        private val names: Map<Int, String>,
    ) : CheckEmperiorContext {
        val logs = mutableListOf<Pair<Int, String>>()
        var setUnited: Int? = null
        override fun isunited(): Int = isunitedValue
        override fun activeNationIds(): List<Int> = active
        override fun cityCountOf(nationId: Int): Int = cityCounts[nationId] ?: 0
        override fun totalCityCount(): Int = total
        override fun nationName(nationId: Int): String? = names[nationId]
        override fun pushNationalHistoryLog(nationId: Int, msg: String) {
            logs.add(nationId to msg)
        }
        override fun setIsunited(value: Int) {
            setUnited = value
        }
    }

    @Test
    fun `1국 전도시 소유 → isunited 2 + 전토통일 국가사 로그(조사 이)`() {
        // "촉"(종성 ㄱ) → 조사 "이". (:730-733)
        val ctx = FakeCtx(
            isunitedValue = 0,
            active = listOf(7),
            cityCounts = mapOf(7 to 24),
            total = 24,
            names = mapOf(7 to "촉"),
        )

        checkEmperior(ctx)

        assertEquals(2, ctx.setUnited)
        assertEquals(1, ctx.logs.size)
        assertEquals(7, ctx.logs[0].first)
        assertEquals("<D><b>촉</b></>이 전토를 통일", ctx.logs[0].second)
    }

    @Test
    fun `전토통일 로그 조사(무종성 → 가)`() {
        // "위"(무종성) → 조사 "가".
        val ctx = FakeCtx(0, listOf(3), mapOf(3 to 24), 24, mapOf(3 to "위"))

        checkEmperior(ctx)

        assertEquals(2, ctx.setUnited)
        assertEquals("<D><b>위</b></>가 전토를 통일", ctx.logs[0].second)
    }

    @Test
    fun `국가 2국 이상이면 no-op`() {
        val ctx = FakeCtx(0, listOf(1, 2), mapOf(1 to 12, 2 to 12), 24, mapOf(1 to "위", 2 to "촉"))

        checkEmperior(ctx)

        assertNull(ctx.setUnited)
        assertTrue(ctx.logs.isEmpty())
    }

    @Test
    fun `1국이지만 전 도시 소유 아니면 no-op`() {
        val ctx = FakeCtx(0, listOf(7), mapOf(7 to 23), 24, mapOf(7 to "촉"))

        checkEmperior(ctx)

        assertNull(ctx.setUnited)
        assertTrue(ctx.logs.isEmpty())
    }

    @Test
    fun `1국이지만 도시 수 0이면 no-op`() {
        val ctx = FakeCtx(0, listOf(7), mapOf(7 to 0), 24, mapOf(7 to "촉"))

        checkEmperior(ctx)

        assertNull(ctx.setUnited)
        assertTrue(ctx.logs.isEmpty())
    }

    @Test
    fun `이미 isunited != 0 이면 no-op`() {
        val ctx = FakeCtx(2, listOf(7), mapOf(7 to 24), 24, mapOf(7 to "촉"))

        checkEmperior(ctx)

        assertNull(ctx.setUnited)
        assertTrue(ctx.logs.isEmpty())
    }
}
