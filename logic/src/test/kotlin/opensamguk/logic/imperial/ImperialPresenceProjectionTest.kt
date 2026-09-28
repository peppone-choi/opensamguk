package opensamguk.logic.imperial

import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImperialPresenceProjectionTest {
    private val house = ImperialHouse("later_han", "後漢", ImperialLineStatus.ACTIVE,
        1, null, emptyList(), null, 5, 46, 70)
    private fun at(node: StrategicNodeRef) = GeneralPositionState("r1", "a".repeat(64), 1, node, 1)

    @Test
    fun `emperor badge uses spatial province and city only when physically at reference city`() {
        val world = ImperialWorldState(listOf(house), emptyList(), emptyList())
        val home = StrategicNodeRef.LandProvince("home")
        val elsewhere = StrategicNodeRef.LandProvince("cityless")
        assertEquals(listOf(ImperialPresenceBadge("later_han", "後漢", 1, home, 130, 46)),
            ImperialPresenceProjection.badges(world, mapOf(1 to 130), mapOf(1 to at(home)), mapOf(130 to "home")))
        assertEquals(listOf(ImperialPresenceBadge("later_han", "後漢", 1, elsewhere, null, 46)),
            ImperialPresenceProjection.badges(world, mapOf(1 to 130), mapOf(1 to at(elsewhere)), mapOf(130 to "home")))
        assertFailsWith<IllegalArgumentException> {
            ImperialPresenceProjection.badges(world, mapOf(1 to 130), emptyMap(), mapOf(130 to "home"))
        }
    }

    @Test
    fun `vacant lines do not make living emperor badges`() {
        val world = ImperialWorldState(listOf(house.copy(status = ImperialLineStatus.VACANT,
            holderGeneralId = null)), emptyList(), emptyList())
        assertEquals(emptyList(), ImperialPresenceProjection.badges(world, emptyMap(), emptyMap(), emptyMap()))
    }
}
