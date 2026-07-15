package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Q14 `checkEmperior` 천하통일 탐지 단위 테스트 — PHP `func_gamerule.php:696-939`.
 * Detection이 PHP :725-939 tail을 statement order로 실행하는지 검증한다.
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
        val globalLogs = mutableListOf<String>()
        val calls = mutableListOf<String>()
        var setUnited: Int? = null
        var refreshFactor: Int? = null
        override fun isunited(): Int = isunitedValue
        override fun year(): Int = 200
        override fun month(): Int = 5
        override fun hiddenSeed(): String = "seed"
        override fun activeNationIds(): List<Int> = active
        override fun cityCountOf(nationId: Int): Int = cityCounts[nationId] ?: 0
        override fun totalCityCount(): Int = total
        override fun nationName(nationId: Int): String? = names[nationId]
        override fun checkStatistic() { calls += "statistic" }
        override fun pushNationalHistoryLog(nationId: Int, msg: String) {
            calls += "log:$nationId"
            logs.add(nationId to msg)
        }
        override fun pushPreformattedGlobalHistoryLog(msg: String) {
            calls += "global"
            globalLogs += msg
        }
        override fun closeActiveUniqueAuctions() { calls += "auction" }
        override fun grantUnifierInheritancePoint(nationId: Int, points: Int) { calls += "unifier:$nationId:$points" }
        override fun runUnitedEvent() { calls += "united" }
        override fun mergeAndApplyInheritance() { calls += "inherit" }
        override fun setIsunited(value: Int) {
            calls += "isunited:$value"
            setUnited = value
        }
        override fun multiplyRefreshLimit(factor: Int) { calls += "refresh:$factor"; refreshFactor = factor }
        override fun checkHallForEligibleUserGenerals() { calls += "hall" }
        override fun persistUnificationArchive(nationId: Int, josaYi: String) { calls += "archive:$nationId:$josaYi" }
        override fun logHistory() { calls += "yearbook" }
        override fun raiseInvaderMessages() { calls += "invader" }
    }

    @Test
    fun `1국 전도시 소유는 PHP 725-939 tail을 순서대로 실행한다`() {
        val ctx = FakeCtx(
            isunitedValue = 0,
            active = listOf(7),
            cityCounts = mapOf(7 to 24),
            total = 24,
            names = mapOf(7 to "촉"),
        )

        checkEmperior(ctx)

        assertEquals(2, ctx.setUnited)
        assertEquals(100, ctx.refreshFactor)
        assertEquals(7 to "<D><b>촉</b></>이 전토를 통일", ctx.logs.single())
        assertEquals(
            "<C>●</>200년 5월:<Y><b>【통일】</b></><D><b>촉</b></>이 전토를 통일하였습니다. " +
                "<span class='hidden_but_copyable'>(서버시드: seed)</span>",
            ctx.globalLogs.single(),
        )
        assertEquals(
            listOf("statistic", "log:7", "auction", "unifier:7:2000", "united", "inherit", "isunited:2", "refresh:100", "hall", "archive:7:이", "global", "yearbook", "invader"),
            ctx.calls,
        )
    }

    @Test
    fun `무종성 국가명은 이 조사로 통일 로그를 만든다`() {
        val ctx = FakeCtx(0, listOf(3), mapOf(3 to 24), 24, mapOf(3 to "위"))

        checkEmperior(ctx)

        assertEquals(3 to "<D><b>위</b></>가 전토를 통일", ctx.logs.single())
        assertEquals(2, ctx.setUnited)
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
