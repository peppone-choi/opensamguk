package opensamguk.infra.seed

import kotlin.test.*
import opensamguk.logic.input.RuleProfile

class ScenarioWarehouseSeedsTest {
    private fun stock(): Map<String, Any> = linkedMapOf("money" to 0, "grain" to Long.MAX_VALUE,
        "iron" to 2L, "timber" to 3, "horses" to 4)
    private fun row(id: Any = 42): Map<String, Any> = mapOf("countyId" to id, "stock" to stock())
    private fun declaration(): Map<String, Any> = mapOf("version" to 1, "units" to "game-resource-v1",
        "source" to "GAME_DESIGN", "topologyRevision" to "fixture-v1", "topologyHash" to "a".repeat(64),
        "warehouses" to listOf(row()))
    private fun decode(value: Any?) = ScenarioWarehouseSeeds.decode(mapOf("warehouses" to value), RuleProfile.HWIHA)

    @Test fun `explicit inventory preserves exact quantities and returns immutable independent map`() {
        val rows = mutableListOf(row(42), row(3))
        val raw = declaration() + ("warehouses" to rows)
        val result = assertNotNull(decode(raw))
        assertEquals(listOf(3, 42), result.warehouses.keys.toList())
        assertEquals(Long.MAX_VALUE, result.warehouses.getValue(42).grain)
        assertEquals(0L, result.warehouses.getValue(42).money)
        assertEquals("fixture-v1", result.topologyRevision)
        assertEquals("a".repeat(64), result.topologyHash)
        rows.clear()
        assertEquals(2, result.warehouses.size)
        assertFailsWith<UnsupportedOperationException> { (result.warehouses as MutableMap).clear() }
        assertTrue(assertNotNull(decode(declaration() + ("warehouses" to emptyList<Any>()))).warehouses.isEmpty())
    }

    @Test fun `absence differs from malformed and declaration requires HWIHA`() {
        for (profile in listOf(null, RuleProfile.SAMMO, RuleProfile.HWIHA)) {
            assertNull(ScenarioWarehouseSeeds.decode(emptyMap(), profile))
        }
        for (profile in listOf(null, RuleProfile.SAMMO)) {
            assertFailsWith<IllegalArgumentException> {
                ScenarioWarehouseSeeds.decode(mapOf("warehouses" to declaration()), profile)
            }
        }
        for (bad in listOf(null, "{}", declaration() - "source", declaration() + ("extra" to 1),
            declaration() + ("version" to 1L), declaration() + ("units" to "historical"),
            declaration() + ("source" to "HISTORY"), declaration() + ("topologyRevision" to " "),
            declaration() + ("topologyHash" to "z".repeat(64)), declaration() + ("topologyHash" to "a".repeat(63)),
            declaration() + ("warehouses" to mapOf<String, Any>()))) {
            assertFailsWith<IllegalArgumentException> { decode(bad) }
        }
    }

    @Test fun `county identities and every resource reject coercion missing fields and duplicates`() {
        for (bad in listOf(0, -1, 42L, 42.0, "42", true)) {
            assertFailsWith<IllegalArgumentException> { decode(declaration() + ("warehouses" to listOf(row(bad)))) }
        }
        for (badRow in listOf(row() - "stock", row() + ("extra" to 0), mapOf("countyId" to 42))) {
            assertFailsWith<IllegalArgumentException> { decode(declaration() + ("warehouses" to listOf(badRow))) }
        }
        assertFailsWith<IllegalArgumentException> { decode(declaration() + ("warehouses" to listOf(row(), row()))) }
        for (key in stock().keys) {
            for (bad in listOf(-1, -1L, 0.0, "0", true, null)) {
                val invalidRow = row() + ("stock" to (stock() + (key to bad)))
                assertFailsWith<IllegalArgumentException> { decode(declaration() + ("warehouses" to listOf(invalidRow))) }
            }
            val invalidRow = row() + ("stock" to (stock() - key))
            assertFailsWith<IllegalArgumentException> { decode(declaration() + ("warehouses" to listOf(invalidRow))) }
        }
        assertFailsWith<IllegalArgumentException> {
            decode(declaration() + ("warehouses" to listOf(row() + ("stock" to (stock() + ("other" to 1))))))
        }
    }
}
