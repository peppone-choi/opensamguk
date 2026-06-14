package opensamguk.logic.event

import opensamguk.logic.betting.BettingInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [OpenNationBettingAction] 의 PHP grand-truth 패러티 핀.
 *
 * 오라클: `legacy/devsam-core/hwe/sammo/Event/Action/OpenNationBetting.php` (run())
 * + `legacy/devsam-core/src/sammo/Util.php` (`joinYearMonth`).
 *
 * loop 27 에서 측정된 4개 misport 결함을 모두 고정한다:
 *  1. reqInheritancePoint — PHP `true` (OpenNationBetting.php:90)  ← 이전 fabricate: false
 *  2. openYearMonth      — PHP `joinYearMonth = year*12 + month - 1` (Util.php:710) ← 이전: `-1` 누락
 *  3. closeYearMonth     — PHP `openYearMonth + 24` (OpenNationBetting.php:48)   ← 이전 fabricate: +120
 *  4. candidates 키       — PHP `$candidates[] = …` 0-기준 정수 인덱스 append
 *                           (OpenNationBetting.php:56,74) ← 국가 id 가 아니라 삽입순 인덱스
 *
 * 모든 기대값은 인용된 PHP 라인에서만 도출한다(fabricate 금지).
 */
class OpenNationBettingActionTest {

    /** [OpenNationBettingAction.run] 이 읽는 env 를 담은 최소 컨텍스트. */
    private fun ctx(env: Map<String, Any?>): EventActionContext =
        object : EventActionContext {
            override val env = env
        }

    /** run() 을 돌리고 kvStorage 에 저장된 [BettingInfo] 를 꺼낸다. */
    private fun openAndLoad(
        year: Int,
        month: Int,
        nationIds: List<Int>,
    ): BettingInfo {
        val kvStorage = HashMap<String, Any>()
        val env = mapOf(
            "year" to year,
            "month" to month,
            "nationIds" to nationIds,
            "kvStorage" to kvStorage,
        )
        OpenNationBettingAction().run(ctx(env))
        // generateBettingId: last_betting_id(0) + 1 = 1 → key = "betting:1"
        val stored = kvStorage[BettingInfo.KV_KEY_PREFIX + 1]
        assertNotNull(stored, "BettingInfo must be stored under 'betting:1'")
        assertTrue(stored is BettingInfo)
        return stored
    }

    @Test
    fun `reqInheritancePoint is true per PHP grand truth`() {
        val info = openAndLoad(year = 200, month = 3, nationIds = listOf(11, 22, 33))
        // PHP Event/Action/OpenNationBetting.php:90 — `reqInheritancePoint: true`
        // (국가 강약 베팅 = 유산포인트 베팅). 이전 Kotlin 은 false 로 misport.
        assertEquals(true, info.reqInheritancePoint, "PHP OpenNationBetting.php:90 sets reqInheritancePoint: true")
    }

    @Test
    fun `openYearMonth equals joinYearMonth year times 12 plus month minus 1`() {
        val year = 200
        val month = 3
        val info = openAndLoad(year = year, month = month, nationIds = listOf(11, 22, 33))
        // PHP src/sammo/Util.php:710 — `joinYearMonth = $year * 12 + $month - 1`,
        // OpenNationBetting.php:47 — `$openYearMonth = Util::joinYearMonth($year, $month)`.
        // 200*12 + 3 - 1 = 2402. 이전 Kotlin(`year*12+month` = 2403)은 1개월 off-by-one.
        assertEquals(year * 12 + month - 1, info.openYearMonth, "PHP Util.php:710 joinYearMonth = year*12+month-1")
        assertEquals(2402, info.openYearMonth, "200년 3월 → joinYearMonth = 2402 (Util.php:710)")
    }

    @Test
    fun `closeYearMonth equals openYearMonth plus 24 not plus 120`() {
        val year = 200
        val month = 3
        val info = openAndLoad(year = year, month = month, nationIds = listOf(11, 22, 33))
        // PHP Event/Action/OpenNationBetting.php:48 — `$closeYearMonth = $openYearMonth + 24;`
        // 이전 Kotlin(+120)은 fabricate. 2402 + 24 = 2426.
        assertEquals(info.openYearMonth + 24, info.closeYearMonth, "PHP OpenNationBetting.php:48 closeYearMonth = open + 24")
        assertEquals(2426, info.closeYearMonth, "open(2402) + 24 = 2426 (OpenNationBetting.php:48)")
    }

    @Test
    fun `candidates are keyed by zero based insertion index not nation id`() {
        val nationIds = listOf(11, 22, 33)
        val info = openAndLoad(year = 200, month = 3, nationIds = nationIds)
        // PHP Event/Action/OpenNationBetting.php:56,74 — `$candidates = []; … $candidates[] = new SelectItem(…)`
        // → 0-기준 정수 인덱스 키(국가 id 가 아니다). 삽입순(LinkedHashMap) 보존.
        assertEquals(listOf(0, 1, 2), info.candidates.keys.toList(), "candidates keyed by 0-based insertion index (OpenNationBetting.php:56,74)")
        // 키는 후보 인덱스, aux.nation 에 국가 id 가 담긴다(FinishNationBetting 의 nationIDMap 역매핑 대상).
        assertEquals(11, info.candidates[0]?.aux?.get("nation"))
        assertEquals(22, info.candidates[1]?.aux?.get("nation"))
        assertEquals(33, info.candidates[2]?.aux?.get("nation"))
    }
}
