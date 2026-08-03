package opensamguk.gameapi.read

import opensamguk.gameapi.dto.GeneralListRow
import opensamguk.gameapi.dto.ReservedCommandItem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W3 GeneralList — 컬럼 계약(권한 tier·선언 순서·remap·BLOCKED 제외)과 셀 추출, 그리고 텍스트 헬퍼
 * (getOfficerLevelText/getHonor)의 PHP 패러티 단위 테스트. 순수 DTO 매핑이라 Spring/DB 불필요.
 */
class GeneralListColumnsTest {

    // ── 권한 tier ────────────────────────────────────────────────────────────────

    @Test
    fun `permission 0 은 P0 컬럼만 노출한다`() {
        val cols = GeneralListColumns.columnsForPermission(0)
        // P0 viewColumns 일부
        assertTrue("no" in cols)
        assertTrue("specialDomestic" in cols)
        assertTrue("age" in cols)
        assertTrue("politics" in cols)
        assertTrue("charm" in cols)
        // P0 customViewColumns
        assertTrue("officerLevelText" in cols)
        assertTrue("bill" in cols)
        assertTrue("refreshScoreTotal" in cols)
        // P1 컬럼은 빠진다
        assertFalse("leadership_exp" in cols)
        assertFalse("officer_city" in cols)
        assertFalse("warnum" in cols)
        assertFalse("reservedCommand" in cols)
        assertFalse("turntime" in cols)
    }

    @Test
    fun `permission 1 이상은 P1 컬럼을 더한다`() {
        val cols = GeneralListColumns.columnsForPermission(1)
        assertTrue("leadership_exp" in cols)
        assertTrue("officer_city" in cols)
        assertTrue("crew" in cols)
        assertTrue("turntime" in cols)
        assertTrue("recent_war" in cols)
        assertTrue("refreshScore" in cols)
        assertTrue("warnum" in cols)
        assertTrue("firenum" in cols)
        assertTrue("reservedCommand" in cols)
    }

    @Test
    fun `permission 2 도 P1 과 동일한 컬럼 집합이다`() {
        // viewColumns/customViewColumns엔 reqPermission 2 컬럼이 없으므로 perm1==perm2.
        assertEquals(
            GeneralListColumns.columnsForPermission(1),
            GeneralListColumns.columnsForPermission(2),
        )
    }

    // ── remap + BLOCKED 제외 ──────────────────────────────────────────────────────

    @Test
    fun `special special2 는 specialDomestic specialWar 로 remap 되어 노출된다`() {
        val cols = GeneralListColumns.columnsForPermission(2)
        assertTrue("specialDomestic" in cols)
        assertTrue("specialWar" in cols)
        // 원본 키는 노출되지 않는다.
        assertFalse("special" in cols)
        assertFalse("special2" in cols)
    }

    @Test
    fun `BLOCKED 컬럼은 어떤 권한에서도 노출되지 않는다`() {
        val cols = GeneralListColumns.columnsForPermission(2)
        // dex1..5, defence_train, refresh_score(_total), autorun_limit, aux, owner_name 모두 제외.
        for (blocked in listOf(
            "dex1", "dex2", "dex3", "dex4", "dex5",
            "defence_train", "refresh_score", "refresh_score_total",
            "autorun_limit", "aux", "owner_name",
        )) {
            assertFalse(blocked in cols, "BLOCKED 컬럼 '$blocked' 가 노출됨")
        }
        // ownerName(camel, remap된 custom 컬럼)은 유지되나 값은 항상 null(셀 테스트에서 확인).
        assertTrue("ownerName" in cols)
    }

    // ── 선언 순서(패러티) ─────────────────────────────────────────────────────────

    @Test
    fun `컬럼 선언 순서가 PHP viewColumns 다음 customViewColumns 순서다`() {
        val cols = GeneralListColumns.columnsForPermission(2)
        // 핵심 앵커들의 상대 순서를 단언(전체 PHP 순서의 대표).
        fun idx(c: String) = cols.indexOf(c)
        assertTrue(idx("no") < idx("name"))
        assertTrue(idx("name") < idx("nation"))
        assertTrue(idx("specialDomestic") < idx("specialWar"))
        // viewColumns(예: firenum)가 customViewColumns(officerLevel)보다 앞.
        assertTrue(idx("firenum") < idx("officerLevel"))
        assertTrue(idx("officerLevel") < idx("officerLevelText"))
        assertTrue(idx("bill") < idx("reservedCommand"))
        // P1 RANK 컬럼이 P1 viewColumns(recent_war) 뒤.
        assertTrue(idx("recent_war") < idx("warnum"))
        assertTrue(idx("recent_war") < idx("refreshScoreTotal"))
        assertTrue(idx("refreshScore") < idx("warnum"))
    }

    // ── 셀 추출 ────────────────────────────────────────────────────────────────────

    @Test
    fun `cell 이 column 키별 행 필드를 추출한다`() {
        val row = sampleRow()
        assertEquals(10, GeneralListColumns.cell(row, "no", 2))
        assertEquals("관우", GeneralListColumns.cell(row, "name", 2))
        assertEquals(81, GeneralListColumns.cell(row, "politics", 2))
        assertEquals(72, GeneralListColumns.cell(row, "charm", 2))
        assertEquals("급습", GeneralListColumns.cell(row, "specialDomestic", 2))
        assertEquals("보병", GeneralListColumns.cell(row, "specialWar", 2))
        assertEquals(120, GeneralListColumns.cell(row, "refreshScoreTotal", 2))
        assertEquals(3, GeneralListColumns.cell(row, "refreshScore", 2))
        assertEquals(12, GeneralListColumns.cell(row, "warnum", 2))
        // ownerName은 §2 BLOCKED → 항상 null.
        assertEquals(null, GeneralListColumns.cell(row, "ownerName", 2))
        // reservedCommand는 구조체 목록 그대로.
        @Suppress("UNCHECKED_CAST")
        val rc = GeneralListColumns.cell(row, "reservedCommand", 2) as List<ReservedCommandItem>
        assertEquals(1, rc.size)
        assertEquals("출병", rc[0].action)
    }

    @Test
    fun `officerLevel 셀은 PHP getOfficerLevel 마스킹을 따른다`() {
        // level 3(비-수뇌) + 호출자 permission 0 → 1로 마스킹.
        val low = sampleRow().copy(officerLevel = 3)
        assertEquals(1, GeneralListColumns.cell(low, "officerLevel", 0))
        // 같은 행이라도 호출자 permission >= 1 이면 raw 노출.
        assertEquals(3, GeneralListColumns.cell(low, "officerLevel", 1))
        // level >= 5는 권한 무관 raw.
        val high = sampleRow().copy(officerLevel = 7)
        assertEquals(7, GeneralListColumns.cell(high, "officerLevel", 0))
    }

    @Test
    fun `getOfficerLevel 마스킹 함수 직접 검증`() {
        assertEquals(1, GeneralListColumns.getOfficerLevel(2, 0))   // 비수뇌 + perm0 → 1
        assertEquals(2, GeneralListColumns.getOfficerLevel(2, 1))   // 비수뇌 + perm1 → raw
        assertEquals(5, GeneralListColumns.getOfficerLevel(5, 0))   // 수뇌 → raw
        assertEquals(12, GeneralListColumns.getOfficerLevel(12, 0)) // 군주 → raw
    }

    // ── 텍스트 헬퍼(PHP 패러티) ────────────────────────────────────────────────────

    @Test
    fun `officerLevelText 는 PHP getOfficerLevelText 와 byte-동일`() {
        // 0..4는 nlevel 무관 공통 명칭.
        assertEquals("재야", GeneralListText.officerLevelText(0, 8))
        assertEquals("일반", GeneralListText.officerLevelText(1, 8))
        assertEquals("종사", GeneralListText.officerLevelText(2, 5))
        assertEquals("군사", GeneralListText.officerLevelText(3, 3))
        assertEquals("태수", GeneralListText.officerLevelText(4, 8))
        // 5..12는 nlevel*100+level 키.
        assertEquals("군주", GeneralListText.officerLevelText(12, 8))   // 812
        assertEquals("황제", GeneralListText.officerLevelText(12, 7))   // 712
        assertEquals("왕", GeneralListText.officerLevelText(12, 6))     // 612
        assertEquals("참모", GeneralListText.officerLevelText(11, 8))   // 811
        assertEquals("제1장군", GeneralListText.officerLevelText(10, 8)) // 810
        assertEquals("주목", GeneralListText.officerLevelText(12, 4))   // 412
        // 표에 없는 조합(opensamguk 확장 nlevel 9 등) → '-'.
        assertEquals("-", GeneralListText.officerLevelText(12, 9))
    }

    @Test
    fun `honor 는 PHP getHonor 와 byte-동일`() {
        assertEquals("전무", GeneralListText.honor(0))
        assertEquals("전무", GeneralListText.honor(639))
        assertEquals("무명", GeneralListText.honor(640))
        assertEquals("무명", GeneralListText.honor(2559))
        assertEquals("신동", GeneralListText.honor(2560))
        assertEquals("영웅", GeneralListText.honor(64000))
        assertEquals("영웅", GeneralListText.honor(77439))
        assertEquals("구세주", GeneralListText.honor(77440))
        assertEquals("구세주", GeneralListText.honor(999999))
    }

    private fun sampleRow() = GeneralListRow(
        no = 10, name = "관우", nation = 1, npc = 0, injury = 0,
        leadership = 90, strength = 97, intel = 75, politics = 81, charm = 72, explevel = 5, dedlevel = 3,
        gold = 1000, rice = 1000, killturn = null, picture = "guanyu.jpg", imgsvr = 0,
        age = 30, specialDomestic = "급습", specialWar = "보병", personal = "의리",
        belong = 2, troop = 0, city = 5, specage = 20, specage2 = 25,
        leadershipExp = 10.0, strengthExp = 20.0, intelExp = 5.0,
        experience = 5000, dedication = 900, officerLevel = 7, officerCity = 5,
        crewtype = 0, crew = 1000, train = 80, atmos = 90,
        turntime = "2026-06-03 10:30:45", horse = "None", weapon = "None",
        book = "None", item = "None", recentWar = null,
        refreshScoreTotal = 120, refreshScore = 3,
        warnum = 12, killnum = 30, deathnum = 4, killcrew = 5000, deathcrew = 800, firenum = 7,
        officerLevelText = "제3장군", lbonus = 8, ownerName = null,
        honorText = "신동", dedLevelText = "30품관", bill = 1000,
        reservedCommand = listOf(ReservedCommandItem(action = "출병", arg = mapOf("destCityID" to 6), brief = "출병")),
    )
}
