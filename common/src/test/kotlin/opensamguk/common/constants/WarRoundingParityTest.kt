package opensamguk.common.constants

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F7 FU2 (:common half) — war GameConst confirmation.
 *
 * Four war constants are ALREADY GREEN (`maxAtmosByWar`=150, `maxTrainByWar`=110,
 * `defaultCityWall`=1000, `joinRuinedNPCProp`=0.1) — confirm, do NOT re-add. The ONE remaining
 * "war constant" the plan flags, `maxTrialCountByYear`, is in fact a DERIVED LOCAL in PHP
 * (`func.php:1625-1632` / core2026 `uniqueLottery.ts:197-205`): base 1, raised by walking
 * `GameConst::$maxUniqueItemLimit` while `relYear >= targetYear`. Ported as a function, not a const.
 *
 * The half-away rounding (Util::round / setRound), truncate (Util::toInt), and damage-path ceil
 * parity live in :logic (`opensamguk.logic.util.PhpRound`) and are asserted by PhpRoundTest there
 * (this module has no dependency on :logic).
 */
class WarRoundingParityTest {

    @Test
    fun warConstantsAlreadyGreen() {
        assertEquals(150, GameConst.maxAtmosByWar)
        assertEquals(110, GameConst.maxTrainByWar)
        assertEquals(1000, GameConst.defaultCityWall)
        assertEquals(0.1, GameConst.joinRuinedNPCProp)
    }

    @Test
    fun maxTrialCountByYearWalksUniqueItemLimitLadder() {
        // maxUniqueItemLimit = [[-1,1],[3,2],[10,3],[20,4]]; base 1, raised while relYear >= targetYear.
        assertEquals(1, GameConst.maxTrialCountByYear(-1)) // relYear -1 >= -1 → 1
        assertEquals(1, GameConst.maxTrialCountByYear(0))  // 0 >= -1 → 1; 0 < 3 → stop
        assertEquals(1, GameConst.maxTrialCountByYear(2))  // 2 < 3 → still 1
        assertEquals(2, GameConst.maxTrialCountByYear(3))  // 3 >= 3 → 2; 3 < 10 → stop
        assertEquals(2, GameConst.maxTrialCountByYear(9))
        assertEquals(3, GameConst.maxTrialCountByYear(10)) // 10 >= 10 → 3
        assertEquals(3, GameConst.maxTrialCountByYear(19))
        assertEquals(4, GameConst.maxTrialCountByYear(20)) // 20 >= 20 → 4 (ladder top)
        assertEquals(4, GameConst.maxTrialCountByYear(100))
    }
}
