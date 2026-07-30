package opensamguk.common.rng

import java.security.SecureRandom

/**
 * PHP's native MT_RAND_MT19937 state used by rand(), mt_rand(), and array_rand().
 *
 * Unbounded mt_rand() exposes 31 bits, while bounded draws use the full tempered
 * 32-bit value. Tournament production intentionally starts from an ambient seed;
 * captured replays inject a fixed seed instead.
 */
class PhpMt19937(seed: Int) {
    private val state = IntArray(624)
    private var index = state.size

    init {
        state[0] = seed
        for (i in 1 until state.size) {
            val previous = state[i - 1]
            state[i] = 1812433253 * (previous xor (previous ushr 30)) + i
        }
    }

    fun nextInt(): Int = nextUInt32() ushr 1

    fun modulo(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return nextInt() % bound
    }

    fun range(min: Int, max: Int): Int = boundedRange(min, max, ::nextUInt32)

    fun arrayIndex(size: Int): Int {
        require(size > 0) { "array must not be empty" }
        return range(0, size - 1)
    }

    private fun nextUInt32(): Int {
        if (index >= state.size) twist()

        var value = state[index++]
        value = value xor (value ushr 11)
        value = value xor ((value shl 7) and 0x9d2c5680.toInt())
        value = value xor ((value shl 15) and 0xefc60000.toInt())
        value = value xor (value ushr 18)
        return value
    }

    private fun twist() {
        for (i in state.indices) {
            val bits = (state[i] and UPPER_MASK) or (state[(i + 1) % state.size] and LOWER_MASK)
            var next = state[(i + 397) % state.size] xor (bits ushr 1)
            if ((bits and 1) != 0) next = next xor MATRIX_A
            state[i] = next
        }
        index = 0
    }

    companion object {
        private const val MATRIX_A = 0x9908b0df.toInt()
        private const val UPPER_MASK = 0x80000000.toInt()
        private const val LOWER_MASK = 0x7fffffff
        private const val UINT32_MAX = 0xffff_ffffL
        private const val UINT32_SIZE = 0x1_0000_0000L

        internal fun boundedRange(min: Int, max: Int, nextUInt32: () -> Int): Int {
            require(min <= max) { "min must not exceed max" }
            val span = max.toLong() - min.toLong() + 1L
            require(span <= UINT32_SIZE) { "range must fit an unsigned 32-bit span" }
            if (span == UINT32_SIZE) return nextUInt32()

            var value = nextUInt32()
            if ((span and (span - 1L)) == 0L) {
                return min + (Integer.toUnsignedLong(value) and (span - 1L)).toInt()
            }

            val unsignedSpan = span.toInt()
            val limit = UINT32_MAX - (UINT32_MAX % span) - 1L
            while (Integer.toUnsignedLong(value) > limit) {
                value = nextUInt32()
            }
            return min + Integer.remainderUnsigned(value, unsignedSpan)
        }

        fun ambient(): PhpMt19937 = PhpMt19937(SecureRandom().nextInt())
    }
}
