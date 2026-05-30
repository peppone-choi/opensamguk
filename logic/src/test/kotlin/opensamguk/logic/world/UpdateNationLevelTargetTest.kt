package opensamguk.logic.world

import opensamguk.common.constants.GameConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3 / Task NL1 — `UpdateNationLevel` 0-9 target computation.
 *
 * Port target: `Event/Action/UpdateNationLevel.php:34-66` — the city-count → target-level loop.
 *   - city count = `SELECT nation, count(*) FROM city WHERE LEVEL>=4 GROUP BY nation`. The LEVEL is
 *     the MAP/CityConst static level (the active [CityConstVariant] from F6), NOT nation level.
 *   - target level = the highest `cmpNationLevel` whose `cmpCityCnt <= cityCnt` (the PHP
 *     break-on-`cityCnt < cmpCityCnt`). Thresholds come from [GameConst.nationLevelByCityCnt09]
 *     (the 0-9 APPEND table; 0..7 byte-match legacy, 8/9 are the new tiers at 28/36).
 *   - MONOTONIC: act ONLY when `target > current` (`:66`). Never demotes; equal level = no-op.
 *   - `levelDiff = target - current` drives the unique lottery iteration count (NL3).
 *
 * NL1 freezes the target/levelDiff math + the LEVEL>=4 count source. The side-effect sequence is
 * NL2; the lottery is NL3.
 */
class UpdateNationLevelTargetTest {

    // city-count → target level using the 0-9 thresholds [0,1,2,5,8,11,16,21,28,36].
    private fun target(cityCnt: Int): Int = UpdateNationLevel.targetLevelByCityCnt(cityCnt)

    @Test
    fun `target level walks the 0-9 threshold table`() {
        assertEquals(0, target(0))   // <1 → 방랑군
        assertEquals(1, target(1))   // 1 → 호족
        assertEquals(2, target(2))   // 2..4 → 군벌
        assertEquals(2, target(4))
        assertEquals(3, target(5))   // 5..7 → 주자사
        assertEquals(3, target(7))
        assertEquals(4, target(8))   // 8..10 → 주목
        assertEquals(5, target(11))  // 11..15 → 공
        assertEquals(6, target(16))  // 16..20 → 왕
        assertEquals(7, target(21))  // 21..27 → 황제
        assertEquals(7, target(27))
    }

    @Test
    fun `lv8 and lv9 are reachable at the new appended thresholds`() {
        assertEquals(8, target(28))  // 28..35 → 대황제 (APPEND)
        assertEquals(8, target(35))
        assertEquals(9, target(36))  // 36+ → 천자 (APPEND)
        assertEquals(9, target(99))
    }

    @Test
    fun `target uses the F7 0-9 const, not a local copy`() {
        // Drive the boundary directly off GameConst so the test fails if F7's table drifts.
        for ((lvl, row) in GameConst.nationLevelByCityCnt09.withIndex()) {
            val threshold = (row[2] as Number).toInt()
            assertEquals(lvl, target(threshold), "cityCnt==threshold of lv$lvl should map to lv$lvl")
        }
    }

    @Test
    fun `monotonic guard acts only when target greater than current`() {
        // current 5, cityCnt 16 → target 6 > 5 → diff 1
        assertEquals(1, UpdateNationLevel.computeLevelUp(currentLevel = 5, cityCnt = 16)!!.levelDiff)
        // current 6, cityCnt 16 → target 6 == 6 → null (no-op, no demote)
        assertNull(UpdateNationLevel.computeLevelUp(currentLevel = 6, cityCnt = 16))
        // current 7, cityCnt 5 → target 3 < 7 → null (never demotes)
        assertNull(UpdateNationLevel.computeLevelUp(currentLevel = 7, cityCnt = 5))
        // multi-level jump: current 0, cityCnt 36 → target 9 → diff 9
        val jump = UpdateNationLevel.computeLevelUp(currentLevel = 0, cityCnt = 36)!!
        assertEquals(9, jump.newLevel)
        assertEquals(0, jump.oldLevel)
        assertEquals(9, jump.levelDiff)
    }

    @Test
    fun `WHERE LEVEL gte 4 counts active-CityConst map level grouped by nation`() {
        // The count must read the active CityConst (map) level, NOT nation level.
        // levelMap: 수=1, 진=2, 관=3, 이=4, 소=5, 중=6, 대=7, 특=8 (CityConst.levelMap).
        val che = CityConstRegistry.of("che")
        // 업(1)=특→8 (>=4). 호관(70)=관→3 (<4 — drops out of the count).
        val lvA = che.byId(1)!!.level
        val lvB = che.byId(70)!!.level
        assertTrue(lvA >= 4, "업 should be >=4 (특=8)")
        assertTrue(lvB < 4, "호관 should be <4 (관=3)")

        // nation 10 owns cities {1, 70}; only city 1 has level>=4 → count 1.
        val ownership = listOf(1 to 10, 70 to 10)
        val counts = UpdateNationLevel.cityCountsByNation(ownership, che)
        assertEquals(1, counts[10])
    }

    @Test
    fun `cityCountsByNation excludes cities the active CityConst does not know`() {
        val che = CityConstRegistry.of("che")
        // city id 99999 is not in the che map → contributes 0 (PHP: row absent → not counted).
        val ownership = listOf(99999 to 7, 1 to 7)
        val counts = UpdateNationLevel.cityCountsByNation(ownership, che)
        assertEquals(1, counts[7]) // only the real city 1 counts
    }
}
