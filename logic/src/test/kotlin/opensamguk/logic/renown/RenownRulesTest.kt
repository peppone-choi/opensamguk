package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HwihaRenownRulesTest {
    @Test fun `cost rounds upward at exact boundaries and never becomes zero`() {
        assertEquals(1, HwihaRenownRules.personCost(0, 0, 0, 0, 0))
        assertEquals(1, HwihaRenownRules.personCost(10, 10, 10, 10, 10))
        assertEquals(2, HwihaRenownRules.personCost(10, 10, 10, 10, 11))
        assertEquals(10, HwihaRenownRules.personCost(100, 100, 100, 100, 100))
    }

    @Test fun `above hundred stats remain effective and sum cannot overflow`() {
        assertEquals(16, HwihaRenownRules.personCost(156, 156, 156, 156, 156))
        assertEquals(214748365, HwihaRenownRules.personCost(
            Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { HwihaRenownRules.personCost(60, 60, 60, -1, 60) }
    }

    @Test fun `each of five abilities contributes monotonically and equally`() {
        for (index in 0..4) {
            var previous = 0
            for (value in 0..156) {
                val stats = MutableList(5) { 50 }.apply { this[index] = value }
                val cost = HwihaRenownRules.personCost(stats[0], stats[1], stats[2], stats[3], stats[4])
                assertTrue(cost >= previous)
                assertEquals(HwihaRenownRules.personCost(value, 50, 50, 50, 50), cost)
                previous = cost
            }
        }
    }
}
