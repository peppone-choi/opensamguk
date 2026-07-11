package opensamguk.logic.world

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.logic.golden.JoinDrawRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MakeGeneralInheritanceTest {

    @Test
    fun `fixed inheritance choices skip their random draws in PHP order`() {
        val rng = JoinDrawRecorder(LiteHashDrbg("inherit-join"))

        val result = MakeGeneral.draw(
            rng = rng,
            formLeadership = 55,
            formStrength = 55,
            formIntel = 55,
            cityPool = listOf(10),
            availablePersonality = listOf("che_안전"),
            character = "che_안전",
            turnterm = 60,
            geniusRemaining = 5,
            inheritSpecial = "che_귀병",
            inheritCity = 11,
            inheritBonusStat = listOf(3, 1, 1),
            inheritTurntimeZone = 12,
        )

        assertEquals(11, result.cityId)
        assertEquals(listOf(3, 1, 1), listOf(result.bonusLeadership, result.bonusStrength, result.bonusIntel))
        assertEquals("che_귀병", result.special2)
        assertTrue(result.turntimeSecond in 720..779)
        assertEquals(
            listOf("nextRangeInt", "nextRangeInt", "nextRangeInt", "nextRangeInt"),
            rng.drawStream().map { it.method },
        )
    }
}
