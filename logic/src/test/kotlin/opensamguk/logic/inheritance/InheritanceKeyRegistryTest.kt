package opensamguk.logic.inheritance

import kotlin.test.Test
import kotlin.test.assertEquals

class InheritanceKeyRegistryTest {
    @Test
    fun `registry has 11 entries in exact order with correct pointCoeff`() {
        val entries = InheritanceKeyRegistry.entries
        assertEquals(11, entries.size)
        val expected = listOf(
            InheritanceKey.previous to 1.0,
            InheritanceKey.lived_month to 1.0,
            InheritanceKey.max_belong to 10.0,
            InheritanceKey.max_domestic_critical to 1.0,
            InheritanceKey.active_action to 3.0,
            InheritanceKey.combat to 5.0,
            InheritanceKey.sabotage to 20.0,
            InheritanceKey.unifier to 1.0,
            InheritanceKey.dex to 0.001,
            InheritanceKey.tournament to 1.0,
            InheritanceKey.betting to 10.0,
        )
        assertEquals(expected, entries.map { it.key to it.value.pointCoeff })
    }
}
