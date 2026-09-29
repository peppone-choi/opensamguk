package opensamguk.gameapi.read

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * W3-ChiefCenter — F4StateText의 사령부 관련 순수 함수 단위 테스트.
 *
 * 패러티 타깃:
 *  - `officerLevelText` = PHP `getOfficerLevelText($officerLevel, $nlevel)` (func_converter.php:522-565).
 *
 * 직책 라벨의 현재 표시 계약을 검증한다.
 */
class F4StateTextChiefTest {

    // ── getOfficerLevelText: 국가 레벨별 직책명 ──────────────────────────────────────────────────────
    @Test
    fun `국가 레벨 8(제국)의 군주는 군주, 제1장군 등으로 표시된다`() {
        assertEquals("군주", F4StateText.officerLevelText(12, 8))
        assertEquals("참모", F4StateText.officerLevelText(11, 8))
        assertEquals("제1장군", F4StateText.officerLevelText(10, 8))
        assertEquals("제3모사", F4StateText.officerLevelText(5, 8))
    }

    @Test
    fun `국가 레벨 7(황제국)은 황제·승상·사도 라인`() {
        assertEquals("황제", F4StateText.officerLevelText(12, 7))
        assertEquals("승상", F4StateText.officerLevelText(11, 7))
        assertEquals("사도", F4StateText.officerLevelText(5, 7)) // code 705
    }

    @Test
    fun `국가 레벨 6(왕국)은 왕·광록훈·비서령 라인`() {
        assertEquals("왕", F4StateText.officerLevelText(12, 6))
        assertEquals("비서령", F4StateText.officerLevelText(5, 6)) // code 605
    }

    @Test
    fun `officer_level 0~4는 국가 레벨과 무관하게 공통 직책(nlevel 0 강제)`() {
        // PHP: 0..4 → nlevel=0 강제. 국가 레벨을 8로 줘도 결과 불변.
        assertEquals("태수", F4StateText.officerLevelText(4, 8))
        assertEquals("군사", F4StateText.officerLevelText(3, 7))
        assertEquals("종사", F4StateText.officerLevelText(2, 6))
        assertEquals("일반", F4StateText.officerLevelText(1, 5))
        assertEquals("재야", F4StateText.officerLevelText(0, 8))
    }

    @Test
    fun `정의되지 않은 코드는 하이픈을 반환한다`() {
        // 국가 레벨 7 + officer_level 11은 정의(승상)지만, 존재하지 않는 조합은 '-'.
        // 예: nlevel 5에는 lv 10/9/8/7/6/5만 일부 정의 — lv6은 미정의(606은 nlevel 6) → '-'.
        assertEquals("-", F4StateText.officerLevelText(6, 5)) // code 506 미정의
    }

}
