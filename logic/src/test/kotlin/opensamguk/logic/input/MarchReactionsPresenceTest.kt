package opensamguk.logic.input

import kotlin.test.*

class MarchReactionsPresenceTest {
    private fun presence(raw: Any?) = MarchReactions.presence(mapOf(MarchReactions.META_KEY to raw))

    @Test fun `presence separates missing, malformed, empty and pending records without interpreting them`() {
        assertEquals(MarchReactions.Presence.MISSING, MarchReactions.presence(emptyMap()))
        val empty = MarchReactions.Empty.toMetaValue()
        assertEquals(MarchReactions.Presence.EMPTY, presence(empty))
        assertEquals(MarchReactions.Presence.PENDING, presence(empty + ("interceptions" to listOf(mapOf("orderId" to "o-1")))))
        assertEquals(MarchReactions.Presence.PENDING, presence(empty + ("revision" to 3)), "a newer field is a record we cannot read yet")
        for (bad in listOf(null, "x", mapOf("version" to 9), empty + ("version" to 2), empty - "avoidanceOrders",
            empty + ("interceptions" to "none"))) assertEquals(MarchReactions.Presence.MALFORMED, presence(bad), "$bad")
    }
}
