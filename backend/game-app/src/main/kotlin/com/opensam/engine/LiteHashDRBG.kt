package com.opensam.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

open class LiteHashDRBG(
    protected val seed: String,
    protected var stateIdx: Int = 0,
    bufferIdx: Int = 0,
) : Random() {

    protected var buffer: ByteArray = ByteArray(BUFFER_BYTE_SIZE)
    protected var bufferIdx: Int = 0

    init {
        require(bufferIdx >= 0) { "bufferIdx $bufferIdx < 0" }
        require(bufferIdx < BUFFER_BYTE_SIZE) { "bufferIdx $bufferIdx >= $BUFFER_BYTE_SIZE" }
        require(stateIdx >= 0) { "stateIdx $stateIdx < 0" }
        genHashBlock()
        this.bufferIdx = bufferIdx
    }

    private fun genHashBlock() {
        val stateBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(stateIdx)
            .array()
        val payload = seed.toByteArray(Charsets.UTF_8) + stateBytes
        buffer = MessageDigest.getInstance("SHA-512").digest(payload)
        bufferIdx = 0
        stateIdx += 1
    }

    protected open fun genNextBlock() {
        genHashBlock()
    }

    fun getMaxInt(): Long {
        return MAX_INT
    }

    override fun nextBytes(size: Int): ByteArray {
        require(size > 0) { "$size <= 0" }

        if (bufferIdx + size <= BUFFER_BYTE_SIZE) {
            val next = buffer.copyOfRange(bufferIdx, bufferIdx + size)
            bufferIdx += size
            if (bufferIdx == BUFFER_BYTE_SIZE) {
                genNextBlock()
            }
            return next
        }

        val result = ByteArray(size)
        var resultIdx = 0
        val firstLen = BUFFER_BYTE_SIZE - bufferIdx
        buffer.copyInto(result, destinationOffset = resultIdx, startIndex = bufferIdx, endIndex = BUFFER_BYTE_SIZE)
        resultIdx += firstLen

        var remain = size - firstLen
        while (remain > BUFFER_BYTE_SIZE) {
            genNextBlock()
            buffer.copyInto(result, destinationOffset = resultIdx, startIndex = 0, endIndex = BUFFER_BYTE_SIZE)
            resultIdx += BUFFER_BYTE_SIZE
            remain -= BUFFER_BYTE_SIZE
        }

        genNextBlock()
        if (remain == 0) {
            return result
        }

        buffer.copyInto(result, destinationOffset = resultIdx, startIndex = 0, endIndex = remain)
        bufferIdx = remain
        return result
    }

    fun nextBitsBytes(bits: Int): ByteArray {
        val bytes = (bits + 7) shr 3
        val headBits = bits and 0x7

        val next = nextBytes(bytes)
        if (headBits == 0) {
            return next
        }

        val lastIdx = bytes - 1
        val bitMask = 0xFF ushr (8 - headBits)
        next[lastIdx] = (next[lastIdx].toInt() and bitMask).toByte()
        return next
    }

    private fun nextIntBits(bits: Int): Long {
        val raw = nextBitsBytes(bits)
        val padded = ByteArray(8)
        raw.copyInto(padded, startIndex = 0, endIndex = raw.size)
        return parseU64(padded)
    }

    fun nextLegacyInt(max: Long? = null): Long {
        if (max == null || max == MAX_INT) {
            return nextIntBits(MAX_RNG_SUPPORT_BIT)
        }

        if (max > MAX_INT) {
            throw IllegalArgumentException("Over Max Int")
        }
        if (max == 0L) {
            return 0
        }
        if (max < 0L) {
            return -nextLegacyInt(-max)
        }

        val mask = calcBitMask(max)
        val bits = INT_BIT_MASK_MAP[mask] ?: throw IllegalStateException("Unsupported bit mask: $mask")

        var n = nextIntBits(bits)
        while (n > max) {
            n = nextIntBits(bits)
        }
        return n
    }

    fun nextFloat1(): Double {
        val max = 1L shl MAX_RNG_SUPPORT_BIT
        while (true) {
            val nInt = nextIntBits(MAX_RNG_SUPPORT_BIT + 1)
            if (nInt <= max) {
                return nInt.toDouble() / max.toDouble()
            }
        }
    }

    override fun nextBits(bitCount: Int): Int {
        if (bitCount == 0) {
            return 0
        }
        require(bitCount in 1..32) { "bitCount must be in 0..32, but was $bitCount" }

        val n = nextIntBits(bitCount)
        val mask = if (bitCount == 32) 0xFFFF_FFFFL else (1L shl bitCount) - 1L
        return (n and mask).toInt()
    }

    override fun nextInt(): Int {
        return nextLegacyInt(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun nextInt(until: Int): Int {
        require(until > 0) { "until must be positive" }
        return nextLegacyInt((until - 1).toLong()).toInt()
    }

    override fun nextInt(from: Int, until: Int): Int {
        require(from < until) { "from must be less than until" }
        return nextLegacyInt((until - from - 1).toLong()).toInt() + from
    }

    override fun nextDouble(): Double {
        return nextFloat1()
    }

    override fun nextFloat(): Float {
        return nextFloat1().toFloat()
    }

    override fun nextLong(): Long {
        return nextLegacyInt(MAX_INT)
    }

    override fun nextBoolean(): Boolean {
        return nextBitsBytes(1)[0] != 0.toByte()
    }

    companion object {
        const val MAX_RNG_SUPPORT_BIT: Int = 53
        const val MAX_INT: Long = (1L shl MAX_RNG_SUPPORT_BIT) - 1L
        const val BUFFER_BYTE_SIZE: Int = 64

        private val INT_BIT_MASK_MAP: Map<Long, Int> = (1..MAX_RNG_SUPPORT_BIT).associate { bit ->
            ((1L shl bit) - 1L) to bit
        }

        private fun parseU64(value: ByteArray): Long {
            var result = 0L
            val maxLen = minOf(value.size, 8)
            for (idx in 0 until maxLen) {
                result = result or ((value[idx].toLong() and 0xFFL) shl (idx * 8))
            }
            return result
        }

        private fun calcBitMask(value: Long): Long {
            var n = value
            n = n or (n ushr 1)
            n = n or (n ushr 2)
            n = n or (n ushr 4)
            n = n or (n ushr 8)
            n = n or (n ushr 16)
            n = n or (n ushr 32)
            return n
        }

        fun build(seed: String, idx: Int = 0): LiteHashDRBG {
            return LiteHashDRBG(seed, idx)
        }
    }
}
