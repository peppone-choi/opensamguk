package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SiegeAssaultInputTest {
    @Test fun `selected county is the only accepted argument and has stable readback`() {
        assertEquals(77, SiegeAssaultInput.parse("""{"targetCountyId":77}"""))
        assertEquals("""{"targetCountyId":77}""", SiegeAssaultInput.canonicalJson(77))
        for (raw in listOf(null, "", "{}", """{"targetCountyId":0}""", """{"targetCountyId":-1}""",
            """{"targetCountyId":"77"}""", """{"targetCountyId":77.5}""",
            """{"targetCountyId":77,"targetCountyId":78}""", """{"targetCountyId":77,"actorId":1}""")) {
            assertNull(SiegeAssaultInput.parse(raw), raw)
        }
    }
}
