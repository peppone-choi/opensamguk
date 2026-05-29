package opensamguk.common.rng

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha512Test {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `sha512 of empty string matches FIPS vector`() {
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
            "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            hex(sha512("".toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun `utf8 seed conversion byte length`() {
        // '한' is 3 UTF-8 bytes; ensures we are NOT using UTF-16 or codepoint-as-byte.
        assertEquals(3, bytesLikeToByteArray("한").size)
    }
}
