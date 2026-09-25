package opensamguk.logic.input

import kotlin.test.*

class MarchReactionsTest {
    private val now = Phase(200, 1, 1)
    private fun read(raw: Any?) = MarchReactions.read(mapOf(MarchReactions.META_KEY to raw))

    @Test fun `explicit empty inventory differs from missing state`() {
        assertNull(MarchReactions.read(emptyMap()))
        assertEquals(MarchReactions.Empty, read(MarchReactions.Empty.toMetaValue()))
    }

    @Test fun `missing null malformed and future fields never become an empty inventory`() {
        val valid = MarchReactions.Empty.toMetaValue()
        val bad = listOf(null, emptyMap<String, Any>(), valid - "avoidanceOrders", valid + ("version" to 2),
            valid + ("version" to "1"), valid + ("interceptions" to null), valid + ("installedSchemes" to emptyMap<String, Any>()),
            valid + ("extra" to true))
        for (raw in bad) assertFailsWith<IllegalArgumentException> { read(raw) }
    }

    @Test fun `unresolved records in every reaction inventory prevent clear passage`() {
        for (field in listOf("installedSchemes", "interceptions", "avoidanceOrders")) {
            assertFailsWith<IllegalArgumentException> {
                read(MarchReactions.Empty.toMetaValue() + (field to listOf(mapOf("id" to "unresolved"))))
            }
        }
    }

    @Test fun `corps reaction policies round trip in commander order`() {
        val a = ReactionOrder("o2", 1, 9, 1, now)
        val b = ReactionOrder("o1", 5, 3, 2, now.plus(1))
        val c = ReactionOrder("o3", 5, 4, 2, now)
        val inventory = MarchReactions.of(listOf(a, b), listOf(c))
        assertEquals(listOf(b, a), inventory.interceptions)
        assertEquals(inventory, read(inventory.toMetaValue()))
        assertEquals(MarchReactions.Empty, MarchReactions.of(emptyList(), emptyList()))
        // One corps cannot both intercept and evade; unsorted persisted lists are corruption.
        assertFailsWith<IllegalArgumentException> { MarchReactions.of(listOf(a), listOf(a.copy(nationId = 1))) }
        assertFailsWith<IllegalArgumentException> { read(inventory.toMetaValue() + ("interceptions" to listOf(a.toMetaValue(), b.toMetaValue()))) }
        // An untyped installed-scheme record remains invalid beside valid reaction orders.
        assertFailsWith<IllegalArgumentException> { read(inventory.toMetaValue() + ("installedSchemes" to listOf(a.toMetaValue()))) }
    }

    @Test fun `typed installed schemes survive inventory round trip`() {
        val scheme = InstalledScheme("scheme-1", 4, 2, "p-1", now)
        val inventory = MarchReactions.of(emptyList(), emptyList(), listOf(scheme))
        assertEquals(listOf(scheme), inventory.installedSchemes)
        assertEquals(inventory, read(inventory.toMetaValue()))
    }
}
