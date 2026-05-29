package opensamguk.logic.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F7 FU2 (:logic half) — the FIVE distinct war-path rounding modes (decision #5 / Util.php).
 *
 *  - [phpRound]   = Util::round / Util::setRound = intval(round(v,0)) = HALF-AWAY-FROM-ZERO
 *                   (the processWar wrapper supply-rice decrement applies it in-place; calcDamage rounds with it).
 *  - [phpToInt]   = Util::toInt = intval(v) = TRUNCATE-TOWARD-ZERO (collapse gold/rice, city-reset %d).
 *  - [phpCeil]    = the damage-loop clamp `ceil()` (DISTINCT from Util::round inside calcDamage).
 *  - [clamp]      = Util::clamp / valueFit (max<min → min).
 *  Never stdlib Math.round (half-up) nor kotlin.math.round (half-to-even).
 */
class WarRoundingParityTest {

    @Test
    fun `Util-round and setRound are half-away-from-zero, NOT half-up nor half-even`() {
        assertEquals(3, phpRound(2.5))   // half-up AND half-away agree on positive .5
        assertEquals(-3, phpRound(-2.5)) // half-away (NOT -2 half-to-even, NOT -2 half-up)
        assertEquals(4, phpRound(3.5))   // (NOT 4 vs 4 — but half-even would give 4 too; pin the .5 negative above)
        assertEquals(2, phpRound(2.4))
    }

    @Test
    fun `Util-toInt truncates toward zero`() {
        assertEquals(2, phpToInt(2.9))
        assertEquals(-2, phpToInt(-2.9)) // truncate, NOT floor(-3)
        assertEquals(0, phpToInt(-0.5))
        assertEquals(5, phpToInt(5.0))
    }

    @Test
    fun `damage-loop ceil is true ceiling, negative-safe`() {
        assertEquals(3, phpCeil(2.1))
        assertEquals(3, phpCeil(3.0))
        assertEquals(-2, phpCeil(-2.9)) // ceil(-2.9) = -2 (toward +inf), distinct from toInt(-2)/round(-3)
    }

    @Test
    fun `clamp max less than min returns min (valueFit alias)`() {
        assertEquals(10.0, clamp(5.0, 10.0, 1.0))
        assertEquals(0.0, valueFit(-3.0, 0.0))
    }
}
