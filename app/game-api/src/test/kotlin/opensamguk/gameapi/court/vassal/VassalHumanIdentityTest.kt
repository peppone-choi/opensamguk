package opensamguk.gameapi.court.vassal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VassalHumanIdentityTest {
    @Test fun `a live account is required independently of the NPC label`() {
        assertEquals(true, VassalHumanIdentity.read("21", 0))
        assertEquals(true, VassalHumanIdentity.read("21", 1))
        assertEquals(false, VassalHumanIdentity.read(null, 0))
        assertEquals(false, VassalHumanIdentity.read("0", 2))
        assertEquals(false, VassalHumanIdentity.read("-1", 9))
    }
    @Test fun `malformed contradictory and unknown identity is nullable`() {
        for ((id, npc) in listOf("21" to 2, "21" to 99, "abc" to 0, "021" to 0,
            "9223372036854775808" to 0)) assertNull(VassalHumanIdentity.read(id, npc))
    }
}
