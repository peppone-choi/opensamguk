package opensamguk.logic.inheritance

import kotlin.test.Test
import kotlin.test.assertEquals

class InheritanceKeyRegistryTest {
    @Test
    fun `registry has 11 entries in exact order with correct pointCoeff`() {
        val entries = InheritanceKeyRegistry.entries
        assertEquals(11, entries.size)
        val expected = listOf(
            InheritanceKey.PREVIOUS to 1.0,
            InheritanceKey.LIVED_MONTH to 1.0,
            InheritanceKey.MAX_BELONG to 10.0,
            InheritanceKey.MAX_DOMESTIC_CRITICAL to 1.0,
            InheritanceKey.ACTIVE_ACTION to 3.0,
            InheritanceKey.COMBAT to 5.0,
            InheritanceKey.SABOTAGE to 20.0,
            InheritanceKey.UNIFIER to 1.0,
            InheritanceKey.DEX to 0.001,
            InheritanceKey.TOURNAMENT to 1.0,
            InheritanceKey.BETTING to 10.0,
        )
        assertEquals(expected, entries.map { it.key to it.value.pointCoeff })
    }
}
