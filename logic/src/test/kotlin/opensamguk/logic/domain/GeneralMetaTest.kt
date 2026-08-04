package opensamguk.logic.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeneralMetaTest {

    @Test
    fun `metaInt reads a present Number key`() {
        assertEquals(3, metaInt(linkedMapOf("explevel" to 3), "explevel"))
    }

    @Test
    fun `metaInt missing key returns default 0`() {
        assertEquals(0, metaInt(linkedMapOf<String, Any?>("explevel" to 3), "killturn"))
    }

    @Test
    fun `metaDouble reads a present Number key, missing returns default`() {
        assertEquals(2.5, metaDouble(linkedMapOf("dedication" to 2.5), "dedication"))
        assertEquals(0.0, metaDouble(linkedMapOf<String, Any?>(), "dedication"))
    }

    @Test
    fun `autorunLimit reads the flattened autorun deadline`() {
        assertEquals(2401, linkedMapOf<String, Any?>("autorun_limit" to 2401).autorunLimit())
        assertNull(linkedMapOf<String, Any?>().autorunLimit())
    }

    @Test
    fun `withMeta appends a new key after existing keys (insertion order)`() {
        val base = linkedMapOf<String, Any?>("a" to 1, "b" to 2)
        val next = withMeta(base, "c" to 3)
        assertEquals(listOf("a", "b", "c"), next.keys.toList())
        assertEquals(3, metaInt(next, "c"))
    }

    @Test
    fun `withMeta overwrites an existing key in place, preserving its position`() {
        val base = linkedMapOf<String, Any?>("a" to 1, "b" to 2, "c" to 3)
        val next = withMeta(base, "b" to 20)
        // load-bearing for jsonb byte-parity: overwriting "b" must NOT move it to the end
        assertEquals(listOf("a", "b", "c"), next.keys.toList())
        assertEquals(20, metaInt(next, "b"))
    }
}
