package opensamguk.logic.imperial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImperialPresenceProjectionTest {
    private val house = ImperialHouse("later_han", "後漢", ImperialLineStatus.ACTIVE,
        1, null, emptyList(), null, 5, 46, 70)

    @Test
    fun `emperor badge uses physical location even when the court seat differs`() {
        val world = ImperialWorldState(listOf(house), emptyList(), emptyList())
        assertEquals(listOf(ImperialPresenceBadge("later_han", "後漢", 1, 130, 46)),
            ImperialPresenceProjection.badges(world, mapOf(1 to 130)))
        assertFailsWith<IllegalArgumentException> {
            ImperialPresenceProjection.badges(world, emptyMap())
        }
    }

    @Test
    fun `vacant lines do not make living emperor badges`() {
        val world = ImperialWorldState(listOf(house.copy(status = ImperialLineStatus.VACANT,
            holderGeneralId = null)), emptyList(), emptyList())
        assertEquals(emptyList(), ImperialPresenceProjection.badges(world, emptyMap()))
    }
}
