package opensamguk.logic.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P4 G1 — the draw-recording harness is DRAW-NEUTRAL: on a fixed seed it produces byte-identical RNG output to
 * a bare [RandUtil], and its recorded cursor snapshots advance monotonically with the real DRBG. (Symmetric to
 * the PHP recorder's "run twice, diff byte-identical" stability check.)
 */
class BattleDrawRecorderTest {

    private val seed = "8ebfeb6fa932a181ec9ef43b7473f4c9"

    @Test
    fun `recorder is byte-identical to a bare RandUtil on a fixed seed`() {
        val bare = RandUtil(LiteHashDrbg(seed))
        val rec = BattleDrawRecorder(LiteHashDrbg(seed))

        // The exact mix of draw methods a battle pulls: nextBool(prob), nextRange, nextRangeInt, choice,
        // nextBit, and the short-circuit nextBool(>=1)/(<=0).
        repeat(40) {
            assertEquals(bare.nextBool(0.265), rec.nextBool(0.265), "nextBool(0.265) #$it")
            assertEquals(bare.nextRange(0.9, 1.1), rec.nextRange(0.9, 1.1), 0.0, "nextRange #$it")
            assertEquals(bare.nextRangeInt(10, 80), rec.nextRangeInt(10, 80), "nextRangeInt #$it")
            assertEquals(bare.nextBit(), rec.nextBit(), "nextBit #$it")
            assertEquals(
                bare.choice(listOf("위보", "매복", "반목", "화계", "혼란")),
                rec.choice(listOf("위보", "매복", "반목", "화계", "혼란")),
                "choice #$it",
            )
            // short-circuits: identical value, and the recorder marks them consumed=false.
            assertEquals(bare.nextBool(1.485), rec.nextBool(1.485), "nextBool(>=1) #$it")
            assertEquals(bare.nextBool(-0.2), rec.nextBool(-0.2), "nextBool(<=0) #$it")
        }
    }

    @Test
    fun `short-circuited nextBool records consumed=false with an unchanged cursor`() {
        val rec = BattleDrawRecorder(LiteHashDrbg(seed))
        rec.nextBool(0.265)              // a real consuming draw to move the cursor off (1,0)
        rec.nextBool(1.485)              // prob>=1 → true, NO draw
        rec.nextBool(-0.2)               // prob<=0 → false, NO draw
        val s = rec.drawStream()
        assertEquals(3, s.size)
        assertTrue(s[0].consumed, "the real draw consumes")
        assertFalse(s[1].consumed, "prob>=1 short-circuits (no consume)")
        assertEquals(true, s[1].result)
        assertFalse(s[2].consumed, "prob<=0 short-circuits (no consume)")
        assertEquals(false, s[2].result)
        // a short-circuit leaves the cursor where the previous consuming draw left it.
        assertEquals(s[1].stateIdxBefore, s[2].stateIdxBefore, "no-draw must not advance stateIdx")
        assertEquals(s[1].bufferIdxBefore, s[2].bufferIdxBefore, "no-draw must not advance bufferIdx")
    }

    @Test
    fun `the very first three draws match the battle-01 stream anchors`() {
        // battle-01 anchors (from the PHP recorder): nextBool(0.265)→False@(1,0); nextBool(0.07575)→False@(1,7);
        // nextRange(0.9,1.1)→0.9255@(1,35). This pins the cursor-snapshot semantics against the committed golden.
        val rec = BattleDrawRecorder(LiteHashDrbg("e40b0cdd01d00f70516e8f11d14c0c2b"))
        val r0 = rec.nextBool(0.265)
        val r1 = rec.nextBool(0.07575000000000001)
        val r2 = rec.nextRange(0.9, 1.1)
        val s = rec.drawStream()
        assertEquals(false, r0); assertEquals(1L, s[0].stateIdxBefore); assertEquals(0, s[0].bufferIdxBefore)
        assertEquals(false, r1); assertEquals(1L, s[1].stateIdxBefore); assertEquals(7, s[1].bufferIdxBefore)
        assertEquals(1L, s[2].stateIdxBefore); assertEquals(35, s[2].bufferIdxBefore)
        assertEquals(0.9255223941374594, r2, 0.0)
    }
}
