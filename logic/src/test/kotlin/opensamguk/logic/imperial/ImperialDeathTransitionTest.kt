package opensamguk.logic.imperial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ImperialDeathTransitionTest {
    private val house = ImperialHouse("later_han", "後漢", ImperialLineStatus.ACTIVE,
        1, 2, listOf(2), null, 5, 6, 70)
    private val world = ImperialWorldState(listOf(house), emptyList(), emptyList())
    private val candidates = listOf(ImperialCandidate(2, living = true))

    private fun apply(meta: Map<String, Any?>, deceasedId: Int = 1, requestId: String = "death:1") =
        ImperialDeathTransition.apply(meta, deceasedId, requestId, null, candidates, 189, 4, "EMPEROR_DEATH")

    @Test
    fun `missing court or death of another general leaves world metadata untouched`() {
        val unrelated = mapOf<String, Any?>("nationRulerId" to 1)
        assertSame(unrelated, apply(unrelated))
        val seeded = unrelated + (ImperialWorldCodec.META_KEY to ImperialWorldCodec.write(world))
        assertSame(seeded, apply(seeded, deceasedId = 9))
    }

    @Test
    fun `emperor death changes only the imperial world payload and replays once`() {
        val seeded = mapOf<String, Any?>("nationRulerId" to 1,
            ImperialWorldCodec.META_KEY to ImperialWorldCodec.write(world))
        val once = apply(seeded)
        assertEquals(1, once["nationRulerId"])
        val restored = ImperialWorldCodec.read(once)!!
        assertEquals(2, restored.houses.single().holderGeneralId)
        assertEquals(ImperialTransitionType.DEATH_SUCCESSION, restored.transitions.single().type)
        assertSame(once, apply(once))
        assertFailsWith<IllegalArgumentException> { apply(once, deceasedId = 9) }
    }
}
