package opensamguk.common.constants

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * P3 / AREA F7 / Task FG1 — confirm the P3 scalar constants mirror PHP grand truth
 * (`GameConstBase.php`) and assert the NEW 0-9 `nationLevelByCityCnt09` APPEND variant +
 * the `getNationChiefLevel` 8/9 extension.
 *
 * The 0-9 DECISION (plan §"Nation-level 0-9 decision" = APPEND, not remap): levels 0..7 keep
 * their legacy names / chiefCnt / cityCnt thresholds BYTE-IDENTICALLY (so the legacy 8-entry
 * array stays the parity oracle for 0..7 numerics); lv8/lv9 are TWO NEW tiers appended ABOVE
 * 황제(7). 8/9 content is DEFINED by this plan: lv8=대황제(chiefCnt 8, cityCnt 28),
 * lv9=천자(chiefCnt 8, cityCnt 36). `getNationChiefLevel` gains `8=>5, 9=>5` (mirror the 7/6→5
 * slot) — without which `UpdateNationLevel.php:120` would NPE on a null chief level.
 */
class GameConstP3Test {

    @Test fun p3ScalarConstantsMirrorPhp() {
        // GameConstBase.php:66/68/103/416/156/74
        assertEquals(0, GameConst.basegold)
        assertEquals(2000, GameConst.baserice)
        assertEquals(5000, GameConst.basePopIncreaseAmount)
        assertEquals(80, GameConst.retirementYear)
        assertEquals(12, GameConst.maxChiefTurn)
        assertEquals(0.01, GameConst.exchangeFee, 0.0)
    }

    @Test fun maxUniqueItemLimitSteppedTable() {
        // GameConstBase.php:216-221 — [[-1,1],[3,2],[10,3],[20,4]]
        assertEquals(
            listOf(listOf(-1, 1), listOf(3, 2), listOf(10, 3), listOf(20, 4)),
            GameConst.maxUniqueItemLimit,
        )
    }

    @Test fun allItemsPoolPresent() {
        // GameConstBase.php:258+ — four categories, stepped unique tiers
        assertEquals(setOf("horse", "weapon", "book", "item"), GameConst.allItems.keys)
        assertEquals(2, GameConst.allItems.getValue("horse").getValue("che_명마_15_적토마"))
        assertEquals(0, GameConst.allItems.getValue("book").getValue("che_서적_01_효경전"))
    }

    @Test fun nationLevelByCityCnt09_levels0to7_byteMatchLegacy() {
        // getNationLevelList() (func_gamerule.php:15-28) — [name, chiefCnt, cityCnt]
        val legacy0to7 = listOf(
            listOf("방랑군", 2, 0),
            listOf("호족", 2, 1),
            listOf("군벌", 4, 2),
            listOf("주자사", 4, 5),
            listOf("주목", 6, 8),
            listOf("공", 6, 11),
            listOf("왕", 8, 16),
            listOf("황제", 8, 21),
        )
        assertEquals(legacy0to7, GameConst.nationLevelByCityCnt09.subList(0, 8))
    }

    @Test fun nationLevelByCityCnt09_levels8and9_areTheNewAppendTiers() {
        assertEquals(10, GameConst.nationLevelByCityCnt09.size)
        // 8/9 content DEFINED by this plan (APPEND): lv8=대황제(8,28), lv9=천자(8,36)
        assertEquals(listOf("대황제", 8, 28), GameConst.nationLevelByCityCnt09[8])
        assertEquals(listOf("천자", 8, 36), GameConst.nationLevelByCityCnt09[9])
    }

    @Test fun nationLevelByCityCnt09_cityCountThresholdsAreMonotonic() {
        val cityCnts = GameConst.nationLevelByCityCnt09.map { it[2] as Int }
        assertEquals(listOf(0, 1, 2, 5, 8, 11, 16, 21, 28, 36), cityCnts)
        // strictly increasing → the monotonic level-up gate stays well-defined
        for (i in 1 until cityCnts.size) {
            assert(cityCnts[i] > cityCnts[i - 1]) { "cityCnt thresholds must be strictly increasing" }
        }
    }

    @Test fun getNationChiefLevel_levels0to7_byteMatchLegacy() {
        // func_converter.php:41-52 — [7=>5,6=>5,5=>7,4=>7,3=>9,2=>9,1=>11,0=>11]
        assertEquals(5, GameConst.getNationChiefLevel(7))
        assertEquals(5, GameConst.getNationChiefLevel(6))
        assertEquals(7, GameConst.getNationChiefLevel(5))
        assertEquals(7, GameConst.getNationChiefLevel(4))
        assertEquals(9, GameConst.getNationChiefLevel(3))
        assertEquals(9, GameConst.getNationChiefLevel(2))
        assertEquals(11, GameConst.getNationChiefLevel(1))
        assertEquals(11, GameConst.getNationChiefLevel(0))
    }

    @Test fun getNationChiefLevel_levels8and9_extendMonotonicallyMirroring7and6() {
        // ADD 8=>5, 9=>5 (mirror the 7/6→5 slot) — without these UpdateNationLevel.php:120 NPEs
        assertEquals(5, GameConst.getNationChiefLevel(8))
        assertEquals(5, GameConst.getNationChiefLevel(9))
    }

    @Test fun getNationChiefLevel_outOfRange_returnsNullLikePhpArrayMiss() {
        // PHP `[...][$level]` on a missing key yields null; we mirror that with a nullable return
        assertNull(GameConst.getNationChiefLevelOrNull(10))
        assertNull(GameConst.getNationChiefLevelOrNull(-1))
    }
}
