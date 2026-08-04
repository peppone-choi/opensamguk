package opensamguk.common.rng

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PhpMt19937Test {
    @Test
    fun `matches PHP MT19937 unbounded draws`() {
        val random = PhpMt19937(1)

        assertContentEquals(
            intArrayOf(
                895547922,
                2141438069,
                1546885062,
                2002651684,
                245631,
                275145156,
                649254245,
                2145423170,
            ),
            IntArray(8) { random.nextInt() },
        )
    }

    @Test
    fun `matches PHP MT19937 bounded range and array rand draws`() {
        assertContentEquals(
            intArrayOf(22, 69, 62, 84, 31, 56, 45, 70),
            IntArray(8) { PhpMt19937Holder.seed1Modulo.modulo(100) },
        )
        assertContentEquals(
            intArrayOf(46, 40, 25, 69, 64, 14, 92, 42),
            IntArray(8) { PhpMt19937Holder.seed1Range.range(1, 100) },
        )
        assertContentEquals(
            intArrayOf(1, 5, 0, 2, 1, 1, 5, 5),
            IntArray(8) { PhpMt19937Holder.seed1Array.arrayIndex(6) },
        )
    }

    @Test
    fun `uses PHP low-bit mask for power-of-two ranges`() {
        val random = PhpMt19937(1)

        assertContentEquals(
            intArrayOf(1, 1, 0, 0, 1, 1, 1, 1),
            IntArray(8) { random.range(0, 1) },
        )
    }

    @Test
    fun `power-of-two range consumes exactly one maximum unsigned draw`() {
        val draws = intArrayOf(0xffff_fffe.toInt(), 0xffff_ffff.toInt())
        var cursor = 0

        assertEquals(0, PhpMt19937.boundedRange(0, 1) { draws[cursor++] })
        assertEquals(1, cursor)
        assertEquals(1, PhpMt19937.boundedRange(0, 1) { draws[cursor++] })
        assertEquals(2, cursor)
    }

    private object PhpMt19937Holder {
        val seed1Modulo = PhpMt19937(1)
        val seed1Range = PhpMt19937(1)
        val seed1Array = PhpMt19937(1)
    }
}
