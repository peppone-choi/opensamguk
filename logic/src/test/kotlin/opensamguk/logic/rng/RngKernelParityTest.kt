package opensamguk.logic.rng

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RNG-kernel + serializeSeed parity — research §Cross-cutting #5, OQ6, Units 1/2/3/6/14.
 *
 * Verifies the :common kernel primitives draw in PHP-faithful ORDER and that `serializeSeed`
 * (PHP `Util::simpleSerialize`, src/sammo/Util.php:872) handles ALL THREE seed forks the action
 * pipeline needs:
 *   - the 6-tuple `generalCommand` / `nationCommand` action fork (component 2 = literal,
 *     component 6 = `rawClassName` — the per-action RNG seam),
 *   - the 5-tuple `preprocess` fork (NO classname component).
 *
 * simpleSerialize string length is `mb_strlen` (UTF-8 code points), so each Hangul char counts as 1:
 *   cr_맹훈련 = 6 (c,r,_,맹,훈,련) ; cr_건국 = 5 (c,r,_,건,국) ; che_랜덤임관 = 8 (c,h,e,_,랜,덤,임,관).
 *   (The plan's "str(6,che_랜덤임관)" is a transcription slip — PHP mb_strlen('che_랜덤임관') = 8; the
 *    byte oracle wins.)
 */
class RngKernelParityTest {

    private val hidden = "00000000000000000000000000000000"

    // ---- kernel primitives exist with PHP-faithful draw order ----

    @Test
    fun `kernel exposes required primitives`() {
        val ru = RandUtil(LiteHashDrbg(serializeSeed(hidden, "preprocess", 190, 3, 42)))
        // these must all type-check and run (choiceUsingWeight(LinkedHashMap), choiceUsingWeightPair,
        // nextBool(p), nextRangeInt(lo,hi), nextFloat1)
        ru.nextFloat1()
        ru.nextBool(0.4)
        ru.nextRangeInt(95, 105)
        ru.choiceUsingWeight(linkedMapOf("a" to 1.0))
        ru.choiceUsingWeightPair(listOf("a" to 1.0))
    }

    @Test
    fun `choiceUsingWeight fixed draw equals hand-computed bucket`() {
        // weights fail=1, success=2, normal=3 (sum=6); jsKeyOrder = insertion order.
        val weights = linkedMapOf("fail" to 1.0, "success" to 2.0, "normal" to 3.0)
        val seed = serializeSeed(hidden, "preprocess", 190, 3, 42)

        // hand-derive the bucket from the FIRST float draw — independent of choiceUsingWeight's own code.
        val r = RandUtil(LiteHashDrbg(seed)).nextFloat1()
        var rd = r * 6.0
        var handBucket = "?"
        for ((k, w) in weights) { if (rd <= w) { handBucket = k; break }; rd -= w }

        // pinned literal (float1 = 0.4057163336368016 → rd0 = 2.4343… → success bucket)
        assertEquals("success", handBucket, "hand bucket pinned to the captured draw")
        // choiceUsingWeight on a fresh rng of the same seed lands in the same bucket.
        assertEquals(handBucket, RandUtil(LiteHashDrbg(seed)).choiceUsingWeight(weights))
    }

    @Test
    fun `nextBool then nextRangeInt draw order matches RandomizeCityTradeRate`() {
        // PHP Event/Action/RandomizeCityTradeRate.php:38-39: nextBool(prob) FIRST, then nextRangeInt(95,105).
        // level-5 city prob = 0.4.
        val seed = serializeSeed(hidden, "randomizeCityTradeRate", 190, 3)
        val ru = RandUtil(LiteHashDrbg(seed))
        val bool = ru.nextBool(0.4)
        val trade = ru.nextRangeInt(95, 105)
        // pinned to the captured stream: bool=true, trade=95 (proves the draw ORDER, not just values)
        assertEquals(true, bool)
        assertEquals(95, trade)
        assertTrue(trade in 95..105)
    }

    // ---- serializeSeed: all three forks byte-match the PHP simpleSerialize token shape ----

    @Test
    fun `nationCommand six-tuple action fork byte matches`() {
        assertEquals(
            "str(32,$hidden)|str(13,nationCommand)|int(190)|int(3)|int(42)|str(6,cr_맹훈련)",
            serializeSeed(hidden, "nationCommand", 190, 3, 42, "cr_맹훈련"),
        )
    }

    @Test
    fun `generalCommand six-tuple distinct-class forks byte match`() {
        // che_랜덤임관 = mb_strlen 8 (the distinct che_-prefixed class)
        assertEquals(
            "str(32,$hidden)|str(14,generalCommand)|int(190)|int(3)|int(42)|str(8,che_랜덤임관)",
            serializeSeed(hidden, "generalCommand", 190, 3, 42, "che_랜덤임관"),
        )
        // cr_건국 = mb_strlen 5
        assertEquals(
            "str(32,$hidden)|str(14,generalCommand)|int(190)|int(3)|int(42)|str(5,cr_건국)",
            serializeSeed(hidden, "generalCommand", 190, 3, 42, "cr_건국"),
        )
    }

    @Test
    fun `preprocess five-tuple fork has no classname component`() {
        val sixTuple = serializeSeed(hidden, "generalCommand", 190, 3, 42, "cr_맹훈련")
        val fiveTuple = serializeSeed(hidden, "preprocess", 190, 3, 42)
        // 5-tuple = exactly five components, NO trailing classname str(...)
        assertEquals(
            "str(32,$hidden)|str(10,preprocess)|int(190)|int(3)|int(42)",
            fiveTuple,
        )
        assertEquals(5, fiveTuple.split("|").size)
        assertEquals(6, sixTuple.split("|").size)
        // and they coexist: the 6-tuple action fork and the 5-tuple preprocess fork are distinct shapes.
        assertTrue(sixTuple != fiveTuple)
    }
}
