package opensamguk.common.rng

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val BUFFER_BYTE_SIZE = 64
const val MAX_RNG_SUPPORT_BIT = 53
const val MAX_INT_L = 0x1f_ffff_ffff_ffffL        // 2^53-1
const val MAX_INT_MORE1_L = 0x20_0000_0000_0000L  // 2^53
const val TWO_POW_53_D = 9007199254740992.0       // exact 2^53 divisor

open class LiteHashDrbg(seed: ByteArray, stateIdx: Long = 0, bufferIdx: Int = 0) {
    constructor(seed: String, stateIdx: Long = 0, bufferIdx: Int = 0) : this(bytesLikeToByteArray(seed), stateIdx, bufferIdx)

    private val hq: ByteArray
    private val hqIdxPos: Int
    protected var stateIdx: Long = stateIdx
    protected var buffer: ByteArray = ByteArray(0)
    protected var bufferIdx: Int = 0

    init {
        require(bufferIdx in 0 until BUFFER_BYTE_SIZE) { "bufferIdx $bufferIdx out of range" }
        require(stateIdx >= 0) { "stateIdx $stateIdx < 0" }
        val seedU8 = seed.copyOf()
        hqIdxPos = seedU8.size
        hq = ByteArray(seedU8.size + 4)
        seedU8.copyInto(hq, 0)
        genNextBlock()                 // FIRST: consumes stateIdx 0, advances to 1
        this.bufferIdx = bufferIdx
    }

    protected open fun genNextBlock() {
        ByteBuffer.wrap(hq, hqIdxPos, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(stateIdx.toInt())
        buffer = sha512(hq)
        bufferIdx = 0
        stateIdx += 1
    }

    fun getMaxInt(): Long = MAX_INT_L

    fun nextBytes(bytes: Int, baseBytes: Int? = null): ByteArray {
        val n = bytes
        if (n <= 0) throw IllegalArgumentException("$n <= 0")
        if (bufferIdx + n <= BUFFER_BYTE_SIZE) {
            if (baseBytes == null || n >= baseBytes) {
                val result = buffer.copyOfRange(bufferIdx, bufferIdx + n)
                bufferIdx += n
                if (bufferIdx == BUFFER_BYTE_SIZE) genNextBlock()
                return result
            }
            val result = ByteArray(maxOf(n, baseBytes))
            buffer.copyInto(result, 0, bufferIdx, bufferIdx + n)
            bufferIdx += n
            if (bufferIdx == BUFFER_BYTE_SIZE) genNextBlock()
            return result
        }
        val result = ByteArray(if (baseBytes != null) maxOf(n, baseBytes) else n)
        buffer.copyInto(result, 0, bufferIdx, BUFFER_BYTE_SIZE)
        var offset = BUFFER_BYTE_SIZE - bufferIdx
        var remain = n - offset
        while (remain > BUFFER_BYTE_SIZE) {
            genNextBlock(); buffer.copyInto(result, offset, 0, BUFFER_BYTE_SIZE); offset += BUFFER_BYTE_SIZE; remain -= BUFFER_BYTE_SIZE
        }
        genNextBlock()
        if (remain == 0) return result
        buffer.copyInto(result, offset, 0, remain)
        bufferIdx = remain
        return result
    }

    fun nextBits(bits: Int, baseBytes: Int? = null): ByteArray {
        if (bits <= 0) throw IllegalArgumentException("$bits <= 0")
        val bytes = (bits + 7) shr 3
        val headBits = bits and 0x7
        val result = nextBytes(bytes, baseBytes)
        if (headBits == 0) return result
        result[bytes - 1] = (result[bytes - 1].toInt() and (0xff ushr (8 - headBits))).toByte()
        return result
    }

    private fun _nextInt(bits: Int): Long {
        require(bits <= MAX_RNG_SUPPORT_BIT + 1) { "bits $bits > 54: LE u64 read would overflow signed Long" }  // OQ2 guard: any future >54-bit caller fails loudly
        return ByteBuffer.wrap(nextBits(bits, 8)).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun bitMaskLen(max: Long): Int {
        var n = max
        n = n or (n ushr 1); n = n or (n ushr 2); n = n or (n ushr 4)
        n = n or (n ushr 8); n = n or (n ushr 16); n = n or (n ushr 32)
        return 64 - java.lang.Long.numberOfLeadingZeros(n)
    }

    fun nextInt(max: Long? = null): Long {
        if (max == null || max == MAX_INT_L) return _nextInt(MAX_RNG_SUPPORT_BIT)
        if (max > MAX_INT_L) throw IllegalArgumentException("Over max int")
        if (max == 0L) return 0
        if (max < 0) return -nextInt(-max)
        val bits = bitMaskLen(max)
        var n = _nextInt(bits)
        while (n > max) n = _nextInt(bits)   // INCLUSIVE: n==max accepted
        return n
    }

    fun nextFloat1(): Double {
        while (true) {
            val nInt = _nextInt(MAX_RNG_SUPPORT_BIT + 1)   // 54 bits
            if (nInt < MAX_INT_MORE1_L) return nInt.toDouble() / TWO_POW_53_D
            if (nInt == MAX_INT_MORE1_L) return 1.0
        }
    }
}
