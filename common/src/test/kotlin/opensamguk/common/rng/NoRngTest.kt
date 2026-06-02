package opensamguk.common.rng

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T0.2 — NoRng throw-on-draw invariant (faithful to PHP `NoRNG`). Threading this through a
 * zero-draw path makes the ABSENCE of a draw test-enforced (research §8g "parity = absence").
 */
class NoRngTest {

    private val rng = RandUtil(NoRng())

    @Test
    fun `nextFloat1 throws`() {
        assertFailsWith<MustNotBeReachedException> { rng.nextFloat1() }
    }

    @Test
    fun `nextRangeInt throws (goes through nextInt)`() {
        assertFailsWith<MustNotBeReachedException> { rng.nextRangeInt(1, 5) }
    }

    @Test
    fun `nextBit and the drawing nextBool branch throw`() {
        assertFailsWith<MustNotBeReachedException> { rng.nextBit() }
        assertFailsWith<MustNotBeReachedException> { rng.nextBool(0.5) }
        assertFailsWith<MustNotBeReachedException> { rng.nextBool(0.3) }
    }

    @Test
    fun `choice and shuffle throw`() {
        assertFailsWith<MustNotBeReachedException> { rng.choice(listOf("a", "b", "c")) }
        assertFailsWith<MustNotBeReachedException> { rng.shuffle(listOf(1, 2, 3)) }
        assertFailsWith<MustNotBeReachedException> { rng.choiceUsingWeight(mapOf("a" to 1.0, "b" to 2.0)) }
    }

    @Test
    fun `the non-drawing nextBool short-circuits do NOT throw (faithful to RandUtil prob gates)`() {
        // prob >= 1 and prob <= 0 are resolved by RandUtil WITHOUT a draw, so NoRng is never reached.
        assertTrue(rng.nextBool(1.0))
        assertTrue(!rng.nextBool(0.0))
    }

    @Test
    fun `rngInstance is memoized like PHP NoRNG`() {
        assertSame(NoRng.rngInstance(), NoRng.rngInstance())
    }
}
