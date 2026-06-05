package opensamguk.gameapi.read

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W3-ChiefCenter — F4StateText의 사령부 관련 순수 함수 단위 테스트.
 *
 * 패러티 타깃:
 *  - `officerLevelText` = PHP `getOfficerLevelText($officerLevel, $nlevel)` (func_converter.php:522-565).
 *  - `CHIEF_COMMAND_TABLE` = PHP `GameConst::$availableChiefCommand` (GameConstBase.php:378-415) 순서/코드.
 *
 * 라벨/코드는 PHP에서 byte-for-byte이며 여기서 골든값으로 못 박는다(날조 금지 — 실제 PHP 테이블 그대로).
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

    // ── CHIEF_COMMAND_TABLE: availableChiefCommand 순서/코드 ────────────────────────────────────────
    @Test
    fun `사령부 명령 테이블은 6개 카테고리를 GameConst 순서로 가진다`() {
        val categories = F4StateText.CHIEF_COMMAND_TABLE.map { it.first }
        assertEquals(listOf("휴식", "인사", "외교", "특수", "전략", "기타"), categories)
    }

    @Test
    fun `인사 카테고리는 발령·포상·몰수·부대탈퇴지시 4종`() {
        val 인사 = F4StateText.CHIEF_COMMAND_TABLE.first { it.first == "인사" }.second
        assertEquals(listOf("che_발령", "che_포상", "che_몰수", "che_부대탈퇴지시"), 인사)
    }

    @Test
    fun `전략 카테고리는 8종(필사즉생부터 피장파장까지)`() {
        val 전략 = F4StateText.CHIEF_COMMAND_TABLE.first { it.first == "전략" }.second
        assertEquals(8, 전략.size)
        assertTrue("che_허보" in 전략)
        assertTrue("che_피장파장" in 전략)
    }
}
