package opensamguk.logic.input

import kotlin.test.*

class HwihaMarchReactionsTest {
    private fun read(raw: Any?) = HwihaMarchReactions.read(mapOf(HwihaMarchReactions.META_KEY to raw))

    @Test fun `explicit empty inventory differs from missing state`() {
        assertNull(HwihaMarchReactions.read(emptyMap()))
        assertEquals(HwihaMarchReactions.Empty, read(HwihaMarchReactions.Empty.toMetaValue()))
    }

    @Test fun `missing null malformed and future fields never become an empty inventory`() {
        val valid = HwihaMarchReactions.Empty.toMetaValue()
        val bad = listOf(null, emptyMap<String, Any>(), valid - "avoidanceOrders", valid + ("version" to 2),
            valid + ("version" to "1"), valid + ("interceptions" to null), valid + ("installedSchemes" to emptyMap<String, Any>()),
            valid + ("extra" to true))
        for (raw in bad) assertFailsWith<IllegalArgumentException> { read(raw) }
    }

    @Test fun `unresolved records in every reaction inventory prevent clear passage`() {
        for (field in listOf("installedSchemes", "interceptions", "avoidanceOrders")) {
            assertFailsWith<IllegalArgumentException> {
                read(HwihaMarchReactions.Empty.toMetaValue() + (field to listOf(mapOf("id" to "unresolved"))))
            }
        }
    }
}
