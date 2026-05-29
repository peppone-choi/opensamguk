package opensamguk.common.rng

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiteHashDrbgIntFloatTest {
    private val fx = Json.parseToJsonElement(this::class.java.getResource("/rng/rng-fixtures.json")!!.readText()).jsonObject
    private fun bitsHex(d: Double) = "%016x".format(java.lang.Double.doubleToRawLongBits(d))

    @Test fun `nextInt sequence on HelloWorld`() {
        val rng = LiteHashDrbg("HelloWorld"); val s = fx["intSeq"]!!.jsonObject
        assertEquals(s["i255"]!!.jsonPrimitive.long, rng.nextInt(0xffL))
        assertEquals(s["i65535"]!!.jsonPrimitive.long, rng.nextInt(((1 shl 16) - 1).toLong()))
        assertEquals(s["i4G"]!!.jsonPrimitive.long, rng.nextInt(0xffffffffL))
        assertEquals(s["iDefault"]!!.jsonPrimitive.long, rng.nextInt())
        assertEquals(s["i15"]!!.jsonPrimitive.long, rng.nextInt(0x0fL))
        assertEquals(s["i18"]!!.jsonPrimitive.long, rng.nextInt(0x12L))
        assertEquals(s["i99"]!!.jsonPrimitive.long, rng.nextInt(99L))
    }
    @Test fun `nextFloat1 18 draws bit-exact`() {
        val rng = LiteHashDrbg("HelloWorld"); val arr = fx["floatSeq"]!!.jsonArray
        for (i in 0 until 18) assertEquals(arr[i].jsonPrimitive.content, bitsHex(rng.nextFloat1()), "float[$i]")
    }
    @Test fun `nextInt over maxInt throws`() {
        try { LiteHashDrbg("x").nextInt(MAX_INT_L + 1); throw AssertionError("expected throw") }
        catch (e: IllegalArgumentException) { assertEquals("Over max int", e.message) }
    }
    @Test fun `nextInt zero is zero and inclusive max accepted`() {
        val rng = LiteHashDrbg("x")
        assertEquals(0L, rng.nextInt(0L))
        repeat(50) { assertTrue(rng.nextInt(1L) in 0L..1L) }
    }
    @Test fun `nextInt accepts a draw of exactly max (inclusive upper bound)`() {
        // deterministic golden row from a known block: TS oracle pins nextInt(0x99) -> 0x99
        val o = fx["intInclusiveMax"]!!.jsonObject
        val max = o["max"]!!.jsonPrimitive.long
        val draw = LiteHashDrbg("inclusiveMax").nextInt(max)
        assertEquals(o["draw"]!!.jsonPrimitive.long, draw)
        assertEquals(max, draw, "draw of EXACTLY max must be accepted (n==max), not rejected")
    }
    @Test fun `alignment stress sequence`() {
        val rng = LiteHashDrbg("alignStress")
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        for (entry in fx["alignStress"]!!.jsonArray) {
            val p = entry.jsonArray
            when (p[0].jsonPrimitive.content) {
                "bits7"  -> assertEquals(p[1].jsonPrimitive.content, hex(rng.nextBits(7)))
                "bytes1" -> assertEquals(p[1].jsonPrimitive.content, hex(rng.nextBytes(1)))
                "int99"  -> assertEquals(p[1].jsonPrimitive.long, rng.nextInt(99L))
            }
        }
    }
}
