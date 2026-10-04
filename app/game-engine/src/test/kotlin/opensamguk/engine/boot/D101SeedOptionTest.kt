package opensamguk.engine.boot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class D101SeedOptionTest {

    @Test
    fun `reset capacity is explicit when present and rejects invalid values`() {
        assertEquals(null, SeedBootstrap.resolveMaxGeneral(null))
        assertEquals(50, SeedBootstrap.resolveMaxGeneral("50"))
        assertEquals(50, SeedBootstrap.resolveMaxGeneral(" 50 "))
        for (invalid in listOf("0", "10000", "-1", "١", "50.0", "missing")) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                SeedBootstrap.resolveMaxGeneral(invalid)
            }
        }
    }

}
