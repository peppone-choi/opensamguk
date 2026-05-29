package opensamguk.common.rng

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LiteHashDrbgBytesTest {
    private val fx: JsonObject = Json.parseToJsonElement(
        this::class.java.getResource("/rng/rng-fixtures.json")!!.readText()).jsonObject
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun `helloWorld 5 blocks match SHA + LE-append`() {
        assertEquals(fx["helloWorldBlocks"]!!.jsonPrimitive.content, hex(LiteHashDrbg("HelloWorld").nextBytes(64 * 5)))
    }
    @Test fun `byte sequence with baseBytes pad`() {
        val rng = LiteHashDrbg("HelloWorld"); val s = fx["bytesSeq"]!!.jsonObject
        assertEquals(s["b10"]!!.jsonPrimitive.content, hex(rng.nextBytes(10)))
        assertEquals(s["b32"]!!.jsonPrimitive.content, hex(rng.nextBytes(32)))
        assertEquals(s["b1"]!!.jsonPrimitive.content, hex(rng.nextBytes(1)))
        assertEquals(s["b64"]!!.jsonPrimitive.content, hex(rng.nextBytes(64)))
        assertEquals(s["b5"]!!.jsonPrimitive.content, hex(rng.nextBytes(5)))
        assertEquals(s["b16pad18"]!!.jsonPrimitive.content, hex(rng.nextBytes(16, 18)))
    }
    @Test fun `bit sequence`() {
        val rng = LiteHashDrbg("HelloWorld"); val s = fx["bitsSeq"]!!.jsonObject
        for (bits in listOf(10, 4, 15, 32, 7, 99, 512, 1, 2, 3))
            assertEquals(s[bits.toString()]!!.jsonPrimitive.content, hex(rng.nextBits(bits)), "bits=$bits")
    }
    @Test fun `nextBytes(0) throws`() {
        try { LiteHashDrbg("HelloWorld").nextBytes(0); throw AssertionError("expected throw") } catch (e: IllegalArgumentException) {}
    }
    @Test fun `nextBits(0) throws`() {
        try { LiteHashDrbg("HelloWorld").nextBits(0); throw AssertionError("expected throw") } catch (e: IllegalArgumentException) {}
    }
}
