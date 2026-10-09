package opensamguk.logic.economy

import kotlin.test.*

class WarehouseFundingScopeTest {
    private data class County(val id: Int, val nation: Int, val supplied: Boolean)
    private val rows = listOf(County(3, 1, true), County(2, 1, false), County(1, 1, true),
        County(4, 2, true), County(5, 1, true), County(6, 0, true))

    private fun select(payer: Int, location: Int, capital: Int? = 1,
        available: Set<Int> = setOf(1, 2, 3), trace: MutableList<String> = mutableListOf()): List<Int> =
        WarehouseFundingScope.countiesFor(payer, location,
            { id -> trace += "city:$id"; rows.singleOrNull { it.id == id } },
            { trace += "capital"; capital }, { trace += "cities"; rows },
            { it.id }, { it.nation }, { it.supplied }, { id -> trace += "warehouse:$id"; id in available })

    @Test fun `landless missing foreign and neutral locations never read the network`() {
        val trace = mutableListOf<String>()
        assertEquals(emptyList(), select(0, 3, trace = trace))
        assertEquals(emptyList(), trace)
        for (location in listOf(99, 4, 6)) {
            trace.clear()
            assertEquals(emptyList(), select(1, location, trace = trace))
            assertEquals(listOf("city:$location"), trace)
        }
    }

    @Test fun `isolation reads only its own warehouse even when it is capital`() {
        val trace = mutableListOf<String>()
        assertEquals(listOf(2), select(1, 2, capital = 2, trace = trace))
        assertEquals(listOf("city:2", "warehouse:2"), trace)
        trace.clear()
        assertEquals(emptyList(), select(1, 2, available = emptySet(), trace = trace))
        assertEquals(listOf("city:2", "warehouse:2"), trace)
    }

    @Test fun `network evaluates warehouses in roster order then returns capital first`() {
        val trace = mutableListOf<String>()
        assertEquals(listOf(1, 3), select(1, 3, trace = trace))
        assertEquals(listOf("city:3", "capital", "cities", "warehouse:3", "warehouse:1", "warehouse:5"), trace)
        assertEquals(listOf(3, 1), select(1, 3, capital = 3))
        assertEquals(listOf(1, 3), select(1, 3, capital = 2), "an unsupplied capital is excluded")
        assertEquals(listOf(1, 3, 5), select(1, 3, capital = null, available = setOf(1, 3, 5)))
    }
}
