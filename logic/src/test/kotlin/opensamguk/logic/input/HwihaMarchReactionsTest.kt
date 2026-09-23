package opensamguk.logic.input

import kotlin.test.*

class HwihaMarchReactionsTest {
    private val now = HwihaPhase(200, 1, 1)
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

    @Test fun `corps reaction policies round trip in commander order`() {
        val a = HwihaReactionOrder("o2", 1, 9, 1, now)
        val b = HwihaReactionOrder("o1", 5, 3, 2, now.plus(1))
        val c = HwihaReactionOrder("o3", 5, 4, 2, now)
        val inventory = HwihaMarchReactions.of(listOf(a, b), listOf(c))
        assertEquals(listOf(b, a), inventory.interceptions)
        assertEquals(inventory, read(inventory.toMetaValue()))
        assertEquals(HwihaMarchReactions.Empty, HwihaMarchReactions.of(emptyList(), emptyList()))
        // One corps cannot both intercept and evade; unsorted persisted lists are corruption.
        assertFailsWith<IllegalArgumentException> { HwihaMarchReactions.of(listOf(a), listOf(a.copy(nationId = 1))) }
        assertFailsWith<IllegalArgumentException> { read(inventory.toMetaValue() + ("interceptions" to listOf(a.toMetaValue(), b.toMetaValue()))) }
        // Installed schemes remain unsupported even beside valid reaction orders.
        assertFailsWith<IllegalArgumentException> { read(inventory.toMetaValue() + ("installedSchemes" to listOf(a.toMetaValue()))) }
    }
}
