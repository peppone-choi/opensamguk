package com.opensam.engine

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.HexFormat

private const val LITE_DRBG_BLOCK_SIZE = LiteHashDRBG.BUFFER_BYTE_SIZE

private val TEST_VECTOR_BLOCKS = listOf(
    "24d9ccd648556255fd0ee9f5b29918de90617341958b3b354d572167e4dee02b757816a2bbe0b502c52413ffd384381a9d7b4e193df6f4345d6a95e111d661c4",
    "2e9264512f6f4b080cf1376b74fab6878ecf4a6e185942d2e5b22cf923885b9952d40601a414225d6901417fd4ce9368ac77e4a63d3fc9b58ab952bb8c33f165",
    "8e2ebf5af6283a1b18f4c044c86c20d02be3890613c4cc8b7c6b7b35581263b972a82630df69a9289988422d7c3a9be5edf78d5de16fabd01e5dd4e458068d8a",
    "398596047ba547bfe371ec863a3e019ab0dbc4bb3b27e9077685aae4283ff6bbccfd981d92f9358f7efffbb72a940414802d98466d132e2ad0a16a12946d5f47",
    "b3606fe9b18c4aa7315e78bb9e47cb51cc4e203fcc2e631f0405c1b872c8e1cb5b6415ea74bbb77fffaaadb002b47cb4f4628dc0709634365b187667f5c708cb",
)

private val TEST_VECTOR = HexFormat.of().parseHex(TEST_VECTOR_BLOCKS.joinToString(""))

private fun fillLiteBlock(src: ByteArray, filler: ByteArray, length: Int = LITE_DRBG_BLOCK_SIZE): ByteArray {
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

private class LiteHashDummyBlockRng(
    private val repeatBlocks: List<ByteArray>,
    initialStateIdx: Int = 0,
) : LiteHashDRBG(seed = "", stateIdx = 0) {
    private val repeatBlockCnt: Int

    init {
        require(repeatBlocks.isNotEmpty()) { "repeatBlocks is empty" }
        repeatBlocks.forEach { block ->
            require(block.size == LITE_DRBG_BLOCK_SIZE) {
                "Invalid repeat block ${block.size} != $LITE_DRBG_BLOCK_SIZE"
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

class LiteHashDRBGTest {
    @Test
    fun `sha512 block sequence matches php vector`() {
        val rng = LiteHashDRBG.build("HelloWorld")
        val actual = ByteArray(LITE_DRBG_BLOCK_SIZE * TEST_VECTOR_BLOCKS.size)

        for (idx in TEST_VECTOR_BLOCKS.indices) {
            val block = rng.nextBytes(LITE_DRBG_BLOCK_SIZE)
            block.copyInto(actual, destinationOffset = idx * LITE_DRBG_BLOCK_SIZE)
        }

        assertArrayEquals(TEST_VECTOR, actual)
    }

    @Test
    fun `nextBytes matches php offset reads`() {
        val rng = LiteHashDRBG.build("HelloWorld")
        val reads = listOf(10, 32, 1, 64, 5, 16)
        var offset = 0

        for (size in reads) {
            val expected = TEST_VECTOR.copyOfRange(offset, offset + size)
            val actual = rng.nextBytes(size)
            assertArrayEquals(expected, actual)
            offset += size
        }
    }

    @Test
    fun `nextLegacyInt and nextFloat1 match php dummy block vectors`() {
        val pattern = byteArrayOf(
            0x00,
            0x11,
            0x22,
            0x33,
            0x44,
            0x55,
            0x66,
            0x77,
            0x88.toByte(),
            0x99.toByte(),
            0xaa.toByte(),
            0xbb.toByte(),
            0xcc.toByte(),
            0xdd.toByte(),
            0xee.toByte(),
            0xff.toByte(),
        )
        val block = fillLiteBlock(byteArrayOf(), pattern)

        val intRng = LiteHashDummyBlockRng(listOf(block))
        intRng.nextBytes(7)
        assertEquals(0x77L, intRng.nextLegacyInt(0xff))

        val floatRng = LiteHashDummyBlockRng(listOf(block))
        floatRng.nextBytes(11)

        val floatMax = (1L shl LiteHashDRBG.MAX_RNG_SUPPORT_BIT).toDouble()
        val expectedA = 0x1100ffeeddccbbL / floatMax
        val expectedB = 0x08776655443322L / floatMax

        val actualA = floatRng.nextFloat1()
        val actualB = floatRng.nextFloat1()

        assertEquals(expectedA, actualA)
        assertTrue(actualA < 0.5313720384)
        assertTrue(actualA > 0.5313720383)
        assertEquals(expectedB, actualB)
    }
}
