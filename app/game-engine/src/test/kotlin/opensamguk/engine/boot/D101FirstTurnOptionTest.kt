package opensamguk.engine.boot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class D101FirstTurnOptionTest {
    @Test
    fun `explicit immediate first turn is separate from cadence`() {
        assertEquals(60, SeedBootstrap.resolveTurnTerm(null, "60"))
        assertEquals(true, SeedBootstrap.resolveFirstTurn("immediate"))
        assertEquals(false, SeedBootstrap.resolveFirstTurn("scheduled"))
        assertEquals(false, SeedBootstrap.resolveFirstTurn(null))
        for (invalid in listOf("now", "true", "1", "IMMEDIATE")) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                SeedBootstrap.resolveFirstTurn(invalid)
            }
        }
    }
}
