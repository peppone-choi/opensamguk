package opensamguk.logic.war

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port-faithful enumeration of the battle-stat keys threaded through
 * `onCalcStat`/`onCalcOpposeStat` (research Unit 2; plan AREA F4 task FW1; decision #1).
 *
 * PHP grand truth (key string + cross-vs-single classification):
 *   - cross (self onCalcStat THEN oppose onCalcOpposeStat), verified in PHP:
 *       warAvoidRatio        WarUnitGeneral.php:144-145
 *       warCriticalRatio     WarUnitGeneral.php:131-132
 *       warMagicTrialProb    che_계략시도.php:40-41
 *       warMagicSuccessProb  che_계략시도.php:62-63
 *       warMagicSuccessDamage che_계략시도.php:74-75   (aux = magic name, MULTIPLICATIVE)
 *       warMagicFailDamage   che_계략실패.php:29-30 / che_계략발동.php:29-30
 *       bonusTrain           WarUnitGeneral.php:108-109
 *       bonusAtmos           WarUnitGeneral.php:118-119
 *       dex{armType}         WarUnitGeneral.php:94/98 (statName = 'dex'+armType, dynamic)
 *   - single-sided (onCalcStat ONLY, NO oppose cross), verified in PHP:
 *       initWarPhase         WarUnitGeneral.php:76     (oppose not known at maxPhase calc)
 *       criticalDamageRange  WarUnit.php:443           ([min,max] PAIR; the pair hook)
 *       killRice             WarUnitGeneral.php:277
 *       injuryProb           che_부적_태현청생부.php:26
 *       addDex               General.php:443
 */
class WarStatNameTest {

    @Test
    fun `every documented battle stat key is present`() {
        val expected = setOf(
            "warAvoidRatio",
            "warCriticalRatio",
            "warMagicTrialProb",
            "warMagicSuccessProb",
            "warMagicSuccessDamage",
            "warMagicFailDamage",
            "criticalDamageRange",
            "initWarPhase",
            "bonusTrain",
            "bonusAtmos",
            "killRice",
            "injuryProb",
            "addDex",
        )
        // every constant resolves to its literal PHP key string
        for (key in expected) {
            assertTrue(WarStatName.isKnown(key), "WarStatName missing key '$key'")
        }
        // the scalar key set equals the union minus the pair-only key
        assertEquals(expected, WarStatName.ALL_KEYS)
    }

    @Test
    fun `dex key is dynamic per armType`() {
        // statName = 'dex' + armType (e.g. dex0..dex6) — WarUnitGeneral.php:94
        assertEquals("dex0", WarStatName.dexKey(0))
        assertEquals("dex6", WarStatName.dexKey(6))
        assertTrue(WarStatName.isDexKey("dex3"))
        assertFalse(WarStatName.isDexKey("dexX"))
        assertFalse(WarStatName.isDexKey("addDex"))
    }

    @Test
    fun `cross keys fold both onCalcStat and onCalcOpposeStat`() {
        val crossKeys = setOf(
            "warAvoidRatio",
            "warCriticalRatio",
            "warMagicTrialProb",
            "warMagicSuccessProb",
            "warMagicSuccessDamage",
            "warMagicFailDamage",
            "bonusTrain",
            "bonusAtmos",
        )
        for (key in crossKeys) {
            assertTrue(WarStatName.isCross(key), "$key should CROSS (self+oppose) per PHP")
        }
        // dex family crosses (WarUnitGeneral.php:94/98)
        assertTrue(WarStatName.isCross("dex0"), "dex{armType} should CROSS")
        assertTrue(WarStatName.isCross("dex6"), "dex{armType} should CROSS")
    }

    @Test
    fun `single-sided keys fold onCalcStat only`() {
        val singleKeys = setOf(
            "initWarPhase",
            "criticalDamageRange",
            "killRice",
            "injuryProb",
            "addDex",
        )
        for (key in singleKeys) {
            assertFalse(WarStatName.isCross(key), "$key is onCalcStat-only per PHP (no oppose cross)")
        }
    }

    @Test
    fun `criticalDamageRange is the only pair-typed key`() {
        assertTrue(WarStatName.isPair("criticalDamageRange"))
        // every other documented key is scalar
        for (key in WarStatName.ALL_KEYS - "criticalDamageRange") {
            assertFalse(WarStatName.isPair(key), "$key must be scalar, not pair")
        }
    }
}
