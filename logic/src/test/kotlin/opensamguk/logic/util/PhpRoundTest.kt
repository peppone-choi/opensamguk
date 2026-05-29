package opensamguk.logic.util

import kotlin.test.Test
import kotlin.test.assertEquals

class PhpRoundTest {

    @Test
    fun `phpRound is half-away-from-zero (PHP_ROUND_HALF_UP)`() {
        assertEquals(3, phpRound(2.5))
        assertEquals(4, phpRound(3.5))
        assertEquals(2, phpRound(2.4))
        assertEquals(1, phpRound(0.5))
        assertEquals(-3, phpRound(-2.5)) // away-from-zero (OQ2)
    }

    @Test
    fun `numberFormat groups thousands with comma, no decimals`() {
        assertEquals("12,345", numberFormat(12345))
        assertEquals("0", numberFormat(0))
        assertEquals("1,000,000", numberFormat(1000000))
    }

    @Test
    fun `clamp lower-clamps then upper-clamps, max less than min returns min`() {
        assertEquals(5.0, clamp(5.0, 1.0, 10.0))
        assertEquals(0.0, clamp(-1.0, 0.0, null))
        assertEquals(10.0, clamp(20.0, 0.0, 10.0))
        assertEquals(10.0, clamp(5.0, 10.0, 1.0)) // max<min returns min
    }
}
