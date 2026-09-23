package opensamguk.logic.input

import kotlin.test.*

class HwihaMarchReactionsPresenceTest {
    private fun presence(raw: Any?) = HwihaMarchReactions.presence(mapOf(HwihaMarchReactions.META_KEY to raw))

    @Test fun `presence separates missing, malformed, empty and pending records without interpreting them`() {
        assertEquals(HwihaMarchReactions.Presence.MISSING, HwihaMarchReactions.presence(emptyMap()))
        val empty = HwihaMarchReactions.Empty.toMetaValue()
        assertEquals(HwihaMarchReactions.Presence.EMPTY, presence(empty))
        assertEquals(HwihaMarchReactions.Presence.PENDING, presence(empty + ("interceptions" to listOf(mapOf("orderId" to "o-1")))))
        assertEquals(HwihaMarchReactions.Presence.PENDING, presence(empty + ("revision" to 3)), "a newer field is a record we cannot read yet")
        for (bad in listOf(null, "x", mapOf("version" to 9), empty + ("version" to 2), empty - "avoidanceOrders",
            empty + ("interceptions" to "none"))) assertEquals(HwihaMarchReactions.Presence.MALFORMED, presence(bad), "$bad")
    }
}
