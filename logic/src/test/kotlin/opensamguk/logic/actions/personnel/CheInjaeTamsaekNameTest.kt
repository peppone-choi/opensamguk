package opensamguk.logic.actions.personnel

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals

class CheInjaeTamsaekNameTest {

    @Test
    fun `random-name pool appends 2 when one prefixed general already uses the same base name`() {
        val seed = "duplicate-scout-name"
        val expectedBase = RandUtil(LiteHashDrbg(seed)).let { rng ->
            listOf(
                rng.choice(GameConst.randGenFirstName),
                rng.choice(GameConst.randGenMiddleName),
                rng.choice(GameConst.randGenLastName),
            ).joinToString("")
        }

        val picked = CheInjaeTamsaek.pickRandomGeneralName(
            rng = RandUtil(LiteHashDrbg(seed)),
            existingGeneralNames = listOf("ⓜ$expectedBase"),
        )

        assertEquals("${expectedBase}2", picked)
    }

    @Test
    fun `random-name pool rerolls when the duplicated possession NPC prefix counts twice like PHP`() {
        val seed = "duplicate-possession-npc-name"
        val expectedNames = RandUtil(LiteHashDrbg(seed)).let { rng ->
            List(2) {
                listOf(
                    rng.choice(GameConst.randGenFirstName),
                    rng.choice(GameConst.randGenMiddleName),
                    rng.choice(GameConst.randGenLastName),
                ).joinToString("")
            }
        }

        val picked = CheInjaeTamsaek.pickRandomGeneralName(
            rng = RandUtil(LiteHashDrbg(seed)),
            existingGeneralNames = listOf("ⓝ${expectedNames[0]}"),
        )

        assertEquals(expectedNames[1], picked)
    }
}
