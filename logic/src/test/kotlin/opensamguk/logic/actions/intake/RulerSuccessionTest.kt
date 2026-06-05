package opensamguk.logic.actions.intake

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 군주 후계 후보 산출 골든 — `func.php:1807 nextRuler`의 npcmatch2 + 동률-수집 **버그 재현** 검증.
 */
class RulerSuccessionTest {

    @Test
    fun `npcMatch2 — 75 이하 직접거리, 75 초과 원형거리`() {
        assertEquals(5, RulerSuccession.npcMatch2(10, 5))    // |10-5|=5
        assertEquals(75, RulerSuccession.npcMatch2(80, 5))   // |75|=75 (경계, 직접)
        assertEquals(50, RulerSuccession.npcMatch2(100, 0))  // |100|=100>75 → 150-100
        assertEquals(70, RulerSuccession.npcMatch2(0, 80))   // |80|=80>75 → 150-80
        assertEquals(0, RulerSuccession.npcMatch2(30, 30))   // 동일
    }

    // matchOf=Int 그대로인 헬퍼
    private fun collect(vararg matches: Int): List<Int> =
        RulerSuccession.collectTiedCandidates(matches.toList()) { it }

    @Test
    fun `버그 — minMatch 0이면 선두 0-런만 수집`() {
        // raw ORDER BY asc = [0,0,5,5]. minBool=false. 5에서 break.
        assertEquals(listOf(0, 0), collect(0, 0, 5, 5))
    }

    @Test
    fun `버그 — minMatch 0이고 전부 0이면 전체`() {
        assertEquals(listOf(0, 0, 0), collect(0, 0, 0))
    }

    @Test
    fun `버그 — minMatch 비0이면 전체 수집 (의도는 동률만)`() {
        // raw = [3,3,7]. 의도: 동률 [3,3]만. 버그: 전체 [3,3,7] (절대 break 안 함).
        assertEquals(listOf(3, 3, 7), collect(3, 3, 7))
    }

    @Test
    fun `버그 — minMatch 비0 단일도 수집`() {
        assertEquals(listOf(5), collect(5))
    }

    @Test
    fun `빈 후보 — 빈 리스트`() {
        assertEquals(emptyList(), collect())
    }
}
