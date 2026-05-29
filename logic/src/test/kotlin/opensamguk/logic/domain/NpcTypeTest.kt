package opensamguk.logic.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FD0 — NPCType taxonomy guard (research OQ8). `getNpcType` mirrors PHP `getNPCType()` returning the
 * `npc_state` value; the three load-bearing predicates pin the `>= 2`, `!= 5`, `== 9` thresholds.
 */
class NpcTypeTest {

    private fun general(npc: Int) = General(
        id = 1, nationId = 0, cityId = 0,
        leadership = 50, strength = 50, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 1000, npcType = npc,
    )

    @Test
    fun `getNpcType returns the general npcType verbatim`() {
        for (v in listOf(0, 1, 2, 3, 5, 6, 9)) {
            assertEquals(v, NpcType.getNpcType(general(v)))
        }
    }

    @Test
    fun `lottery short-circuit fires at npc 2 and above only`() {
        assertFalse(NpcType.isLotteryShortCircuit(general(0)))   // user — draws
        assertFalse(NpcType.isLotteryShortCircuit(general(1)))   // NPC basic — draws
        assertTrue(NpcType.isLotteryShortCircuit(general(2)))    // NPC-lite — short-circuit
        assertTrue(NpcType.isLotteryShortCircuit(general(3)))
        assertTrue(NpcType.isLotteryShortCircuit(general(5)))
        assertTrue(NpcType.isLotteryShortCircuit(general(9)))
    }

    @Test
    fun `gennum decrements on retire for every npc type except 5`() {
        assertTrue(NpcType.decrementsGennumOnRetire(general(0)))
        assertTrue(NpcType.decrementsGennumOnRetire(general(1)))
        assertTrue(NpcType.decrementsGennumOnRetire(general(9)))
        assertFalse(NpcType.decrementsGennumOnRetire(general(5)))   // npc=5 keeps gennum
    }

    @Test
    fun `npc 9 is the foreign 이민족 type`() {
        assertTrue(NpcType.isForeign(general(9)))
        assertFalse(NpcType.isForeign(general(0)))
        assertFalse(NpcType.isForeign(general(5)))
    }

    @Test
    fun `taxonomy constants match the observed values`() {
        assertEquals(0, NpcType.USER)
        assertEquals(1, NpcType.NPC_BASIC)
        assertEquals(2, NpcType.NPC_LITE)
        assertEquals(3, NpcType.NPC_3)
        assertEquals(5, NpcType.NPC_NO_GENNUM_DROP)
        assertEquals(6, NpcType.NPC_6)
        assertEquals(9, NpcType.FOREIGN)
        assertEquals(2, NpcType.NPC_SHORT_CIRCUIT)
    }
}
