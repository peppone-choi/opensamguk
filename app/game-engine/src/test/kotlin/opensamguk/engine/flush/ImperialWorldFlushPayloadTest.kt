package opensamguk.engine.flush

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.imperial.ImperialHouse
import opensamguk.logic.imperial.ImperialLineStatus
import opensamguk.logic.imperial.ImperialWorldCodec
import opensamguk.logic.imperial.ImperialWorldState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ImperialWorldFlushPayloadTest {
    private fun payload(meta: Map<String, Any?>): Map<String, Any?> {
        val state = TurnWorldState(1, 200, 1, 3600, Instant.parse("0200-01-01T00:00:00Z"), meta = meta)
        val world = InMemoryTurnWorld(WorldSnapshot(state = state, worldId = WorldId(1)))
        return DatabaseHooks.toFlushPayload(world, ChangeRecorder(), world.consumeDirtyState()).worldStateUpdate
    }

    @Test
    fun `unseeded world omits imperial flush field`() {
        assertFalse("imperial_world" in payload(mapOf("unrelated" to "keep")))
    }

    @Test
    fun `seeded world carries validated imperial state without other meta keys`() {
        val imperial = ImperialWorldState(listOf(ImperialHouse("test_line", "시험 계통",
            ImperialLineStatus.ACTIVE, 1, null, emptyList(), null, null, 5, 50)),
            emptyList(), emptyList())
        val encoded = ImperialWorldCodec.write(imperial)
        assertEquals(encoded, payload(mapOf("unrelated" to "keep", ImperialWorldCodec.META_KEY to encoded))["imperial_world"])
    }

    @Test
    fun `malformed seeded world rejects flush rather than dropping the key`() {
        assertFailsWith<IllegalArgumentException> {
            payload(mapOf(ImperialWorldCodec.META_KEY to mapOf("broken" to true)))
        }
    }
}
