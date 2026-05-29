package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A3 / Task NL5 — pin the `WHERE LEVEL>=4` per-map nation-level count semantics (golden-derived).
 *
 * RESOLVED (consolidated OQ #2 gap; city-level-convention memory): the legacy count source is the
 * LITERAL query `SELECT nation, count(*) FROM city WHERE LEVEL>=4 GROUP BY nation`
 * (`UpdateNationLevel.php:36`) — there is **NO 한족/이민족 filter**. So a lv4 이민족 ('이') city owned
 * by a 한족 nation **DOES count** toward that nation's level. The map levels (CityConst.levelMap) are
 * 수=1, 진=2, 관=3, **이=4**, 소=5, 중=6, 대=7, 특=8: 이민족 county-seats are lv4 ('이'), 한족 군 치소
 * are lv5 ('소'). The threshold for the count is the literal `>= 4`, so BOTH lv4 이민족 and lv5 한족
 * cities count; lv3 ('관') and below drop out.
 *
 * Consequence (PINNED): no per-map filter is needed in [UpdateNationLevel.cityCountsByNation] — the
 * single `level < 4 → continue` guard IS the faithful port. This test fixes that decision so a future
 * "exclude 이민족 lv4" regression fails loudly. (The numeric byte-match against the live golden is the
 * G1 gate; NL5 freezes the include-vs-exclude semantics here so G1 cannot silently flip it.)
 */
class CityLevelCountSemanticsTest {

    private val che = CityConstRegistry.of("che")

    @Test
    fun `che map levels confirm the 이4 소5 convention`() {
        assertEquals(4, che.byId(63)!!.level, "강 = 이민족 county-seat → lv4 ('이')")
        assertEquals(5, che.byId(35)!!.level, "진양 = 한족 군 치소 → lv5 ('소')")
        assertEquals(3, che.byId(70)!!.level, "호관 = 관 → lv3 (drops out of the count)")
    }

    @Test
    fun `a Han nation owning a lv4 이민족 city COUNTS it (no 한족 filter)`() {
        // 한족 nation 7 owns 강(63, 이=4) + 진양(35, 소=5) + 호관(70, 관=3).
        val ownership = listOf(63 to 7, 35 to 7, 70 to 7)
        val counts = UpdateNationLevel.cityCountsByNation(ownership, che)
        // 강(lv4) + 진양(lv5) count; 호관(lv3) does NOT → 2.
        assertEquals(2, counts[7], "lv4 이민족 + lv5 한족 both count; lv3 excluded")
    }

    @Test
    fun `lv5plus city always counts and lv3 and below never count`() {
        val onlyLv5 = UpdateNationLevel.cityCountsByNation(listOf(35 to 9), che)
        assertEquals(1, onlyLv5[9])

        val onlyLv3 = UpdateNationLevel.cityCountsByNation(listOf(70 to 9), che)
        assertEquals(null, onlyLv3[9], "a nation owning only lv3 cities has no LEVEL>=4 count row")
    }

    @Test
    fun `an all-이민족 nation reaches a level purely from lv4 cities`() {
        // a nation holding 5 이민족 (lv4) cities → cityCnt 5 → targetLevel 3 (주자사, threshold 5).
        val iminjok = listOf(63, 64, 65, 66, 67).map { it to 11 } // 강/저/흉노/남만/산월
        val counts = UpdateNationLevel.cityCountsByNation(iminjok, che)
        assertEquals(5, counts[11])
        assertEquals(3, UpdateNationLevel.targetLevelByCityCnt(counts.getValue(11)))
    }
}
