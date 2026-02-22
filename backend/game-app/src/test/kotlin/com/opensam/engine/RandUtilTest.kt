package com.opensam.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val RAND_UTIL_BLOCK_SIZE = LiteHashDRBG.BUFFER_BYTE_SIZE

private fun fillRandBlock(src: ByteArray, filler: ByteArray, length: Int = RAND_UTIL_BLOCK_SIZE): ByteArray {
    require(filler.isNotEmpty()) { "filler must have length" }

    val result = ByteArray(length)
    val srcLen = minOf(src.size, length)
    src.copyInto(result, destinationOffset = 0, startIndex = 0, endIndex = srcLen)

    var offset = srcLen
    while (offset < length) {
        val copyLen = minOf(filler.size, length - offset)
        filler.copyInto(result, destinationOffset = offset, startIndex = 0, endIndex = copyLen)
        offset += copyLen
    }

    return result
}

private class RandUtilDummyBlockRng(
    private val repeatBlocks: List<ByteArray>,
    initialStateIdx: Int = 0,
) : LiteHashDRBG(seed = "", stateIdx = 0) {
    private val repeatBlockCnt: Int

    init {
        require(repeatBlocks.isNotEmpty()) { "repeatBlocks is empty" }
        repeatBlocks.forEach { block ->
            require(block.size == RAND_UTIL_BLOCK_SIZE) {
                "Invalid repeat block ${block.size} != $RAND_UTIL_BLOCK_SIZE"
            }
        }

        repeatBlockCnt = repeatBlocks.size
        stateIdx = ((initialStateIdx % repeatBlockCnt) + repeatBlockCnt) % repeatBlockCnt
        genNextBlock()
    }

    override fun genNextBlock() {
        buffer = repeatBlocks[stateIdx].copyOf()
        bufferIdx = 0
        stateIdx = (stateIdx + 1) % repeatBlockCnt
    }
}

class RandUtilTest {
    private fun createDummyRng(): RandUtilDummyBlockRng {
        val pattern = byteArrayOf(
            0x17,
            0x16,
            0x15,
            0x14,
            0x13,
            0x12,
            0x11,
            0x10,
        )
        val block = fillRandBlock(byteArrayOf(), pattern)
        return RandUtilDummyBlockRng(listOf(block))
    }

    @Test
    fun `shuffle and shuffleAssoc match php vectors`() {
        val randUtil = RandUtil(createDummyRng())

        assertEquals(listOf(7, 0, 1, 2, 3, 4, 5, 6), randUtil.shuffle((0..7).toList()))
        assertEquals(listOf(0, 8, 1, 2, 3, 4, 5, 6, 7, 9), randUtil.shuffle((0..9).toList()))

        val assocRandUtil = RandUtil(createDummyRng())
        val srcAssoc = linkedMapOf(
            "a" to 0,
            "b" to 1,
            "c" to 2,
            "d" to 3,
            "e" to 4,
            "f" to 5,
            "g" to 6,
            "h" to 7,
        )
        val expectedAssoc = linkedMapOf(
            "h" to 7,
            "a" to 0,
            "b" to 1,
            "c" to 2,
            "d" to 3,
            "e" to 4,
            "f" to 5,
            "g" to 6,
        )

        assertEquals(expectedAssoc, assocRandUtil.shuffleAssoc(srcAssoc))
    }

    @Test
    fun `choice and weighted choices match php vectors`() {
        val randUtil = RandUtil(createDummyRng())

        assertEquals(5, randUtil.choice(listOf(0, 1, 2, 3, 4, 5)))
        assertEquals(8, randUtil.choice(listOf(5, 3, 1, 2, 8, 0)))
        assertEquals(
            "c",
            randUtil.choice(
                linkedMapOf(
                    2 to "t",
                    3 to "q",
                    4 to "x",
                    "c" to "c",
                    "a" to "a",
                    "b" to "b",
                ),
            ),
        )

        assertEquals(
            "c",
            randUtil.choiceUsingWeight(
                linkedMapOf(
                    "a" to 0.1,
                    "b" to 10,
                    "tt" to 2,
                    "x" to -1,
                    "c" to 20,
                    "d" to 0,
                    "e" to 6,
                ),
            ),
        )

        assertEquals(
            "xx",
            randUtil.choiceUsingWeightPair(
                listOf(
                    Pair("xx", 10),
                ),
            ),
        )

        assertEquals(
            "q",
            randUtil.choiceUsingWeightPair(
                listOf(
                    Pair("e", 10.0),
                    Pair("d", 4.0),
                    Pair("c", 0.1),
                    Pair("baba", 0.2),
                    Pair("q", 9.0),
                    Pair("xt", 4.0),
                ),
            ),
        )
    }
}
