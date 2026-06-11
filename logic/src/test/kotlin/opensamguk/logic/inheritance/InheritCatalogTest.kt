package opensamguk.logic.inheritance

import opensamguk.common.constants.GameConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [InheritCatalog] — v_inheritPoint.php:41-63 카탈로그의 byte-패러티 핀.
 *
 * 기대 문자열은 전부 legacy/devsam-core PHP 소스(각 단언의 file:line 주석)에서 전사했고,
 * php:8.3-cli Docker 실행 캡처(2회 byte-identical)로 교차 검증했다. 날조 없음.
 */
class InheritCatalogTest {

    private val sw = InheritCatalog.availableSpecialWar()
    private val uq = InheritCatalog.availableUnique()

    // ── 크기 ──────────────────────────────────────────────────────────────────────

    @Test
    fun `specialwar 카탈로그는 GameConst availableSpecialWar 20종 전부를 담는다`() {
        assertEquals(20, sw.size)
        assertEquals(GameConst.availableSpecialWar, sw.keys.toList())
    }

    @Test
    fun `unique 카탈로그 크기 = allItems의 amount != 0 항목 수(100)`() {
        val expected = GameConst.allItems.values.sumOf { sub -> sub.values.count { it != 0 } }
        assertEquals(100, expected) // horse 20 + weapon 20 + book 20 + item 40
        assertEquals(expected, uq.size)
    }

    // ── 삽입 순서 (패러티 대상: PHP 순회 순서) ─────────────────────────────────────

    @Test
    fun `specialwar 키 순서 = GameConstBase php 192-197 배열 순서`() {
        // GameConstBase.php:193 첫 행 — 'che_귀병', 'che_신산', 'che_환술', 'che_집중', 'che_신중', 'che_반계'
        assertEquals(
            listOf("che_귀병", "che_신산", "che_환술", "che_집중", "che_신중", "che_반계"),
            sw.keys.take(6),
        )
    }

    @Test
    fun `unique 키 순서 = allItems 중첩 순회 순서(famiy 경계 포함)`() {
        val keys = uq.keys.toList()
        // GameConstBase.php $allItems horse 첫 amount!=0 행: 백마, 기주마, 오환마, 백상
        assertEquals(listOf("che_명마_07_백마", "che_명마_07_기주마", "che_명마_07_오환마", "che_명마_07_백상"), keys.take(4))
        // 패밀리 경계: horse 20 → weapon, +20 → book, +20 → item
        assertEquals("che_무기_07_동추", keys[20])
        assertEquals("che_서적_07_위료자", keys[40])
        assertEquals("che_의술_정력견혈산", keys[60])
    }

    @Test
    fun `amount == 0 항목(범용 장비)은 제외된다`() {
        assertFalse("che_명마_01_노기" in uq) // amount 0 (GameConstBase.php $allItems)
        assertFalse("che_치료_환약" in uq) // amount 0
    }

    @Test
    fun `내부 맵 키 순서 = PHP 응답 키 순서`() {
        // v_inheritPoint.php:44-46 — title, info
        assertEquals(listOf("title", "info"), sw.getValue("che_귀병").keys.toList())
        // v_inheritPoint.php:57-61 — title, rawName, info
        assertEquals(listOf("title", "rawName", "info"), uq.getValue("che_명마_07_백마").keys.toList())
    }

    // ── byte-단언 샘플 (명마 2 / 무기 1 / 서적 1 / 도구 2 / 전투특기 1) ───────────

    @Test
    fun `명마 적토마 - BaseStatItem 생성 문자열`() {
        val v = uq.getValue("che_명마_15_적토마")
        // BaseStatItem.php:30 sprintf('%s(+%d)') — '적토마' + 15
        assertEquals("적토마(+15)", v["title"])
        assertEquals("적토마", v["rawName"])
        // BaseStatItem.php:31 sprintf('%s +%d') — '통솔'(ITEM_TYPE 명마, BaseStatItem.php:17) + 15
        assertEquals("통솔 +15", v["info"])
    }

    @Test
    fun `명마 백상 - 생성자 info 접미사 가산`() {
        val v = uq.getValue("che_명마_07_백상")
        assertEquals("백상(+7)", v["title"])
        // BaseStatItem.php:31 '통솔 +7' + che_명마_07_백상.php:14 `$this->info .= "<br>[전투] 공격력 +20%, 소모 군량 +10%, 공격 시 페이즈 -1";`
        assertEquals("통솔 +7<br>[전투] 공격력 +20%, 소모 군량 +10%, 공격 시 페이즈 -1", v["info"])
    }

    @Test
    fun `무기 의천검 - BaseStatItem 생성 문자열`() {
        val v = uq.getValue("che_무기_15_의천검")
        assertEquals("의천검(+15)", v["title"]) // BaseStatItem.php:30
        assertEquals("무력 +15", v["info"]) // BaseStatItem.php:31, ITEM_TYPE 무기 → 무력 (BaseStatItem.php:18)
    }

    @Test
    fun `서적 손자병법 - BaseStatItem 생성 문자열`() {
        val v = uq.getValue("che_서적_15_손자병법")
        assertEquals("손자병법(+15)", v["title"]) // BaseStatItem.php:30
        assertEquals("지력 +15", v["info"]) // BaseStatItem.php:31, ITEM_TYPE 서적 → 지력 (BaseStatItem.php:19)
    }

    @Test
    fun `도구 청낭서 - 클래스 리터럴`() {
        val v = uq.getValue("che_의술_청낭서")
        assertEquals("청낭서(의술)", v["title"]) // che_의술_청낭서.php:15 `protected $name = '청낭서(의술)';`
        assertEquals("청낭서", v["rawName"]) // che_의술_청낭서.php:14 `protected $rawName = '청낭서';`
        // che_의술_청낭서.php:16 `protected $info = '...';`
        assertEquals(
            "[군사] 매 턴마다 자신(100%)과 소속 도시 장수(적 포함 50%) 부상 회복<br>[전투] 페이즈마다 40% 확률로 치료 발동(아군 피해 30% 감소, 부상 회복)",
            v["info"],
        )
    }

    @Test
    fun `도구 둔갑천서 - 클래스 리터럴`() {
        val v = uq.getValue("che_필살_둔갑천서")
        assertEquals("둔갑천서(필살)", v["title"]) // che_필살_둔갑천서.php:9 `protected $name = '둔갑천서(필살)';`
        assertEquals("둔갑천서", v["rawName"]) // che_필살_둔갑천서.php:8 `protected $rawName = '둔갑천서';`
        assertEquals("[전투] 필살 확률 +20%p", v["info"]) // che_필살_둔갑천서.php:10 `protected $info = '[전투] 필살 확률 +20%p';`
    }

    @Test
    fun `전투특기 귀병 - 클래스 리터럴`() {
        val v = sw.getValue("che_귀병")
        assertEquals("귀병", v["title"]) // ActionSpecialWar/che_귀병.php:12 `protected $name = '귀병';`
        // ActionSpecialWar/che_귀병.php:13 `protected $info = '...';`
        assertEquals(
            "[군사] 귀병 계통 징·모병비 -10%<br>[전투] 계략 성공 확률 +20%p,<br>공격시 상대 병종에/수비시 자신 병종 숙련에 귀병 숙련을 가산",
            v["info"],
        )
    }

    // ── 전수(全數) 무결성: 모든 항목 비어있지 않음 ────────────────────────────────

    @Test
    fun `모든 항목의 title-rawName-info 는 비어있지 않다`() {
        for ((k, v) in sw) {
            assertTrue(v.getValue("title").isNotEmpty(), "sw $k title")
            assertTrue(v.getValue("info").isNotEmpty(), "sw $k info")
        }
        for ((k, v) in uq) {
            assertTrue(v.getValue("title").isNotEmpty(), "uq $k title")
            assertTrue(v.getValue("rawName").isNotEmpty(), "uq $k rawName")
            assertTrue(v.getValue("info").isNotEmpty(), "uq $k info")
        }
    }
}
