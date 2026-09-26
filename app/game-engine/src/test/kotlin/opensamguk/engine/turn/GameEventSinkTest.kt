package opensamguk.engine.turn

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKey
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventTurn

class GameEventSinkTest {
    @Test
    fun `world drains typed events once and keeps ordinals across flushes`() {
        val bootstrapped = mutableListOf<EventTurn>()
        val world = InMemoryTurnWorld(
            WorldSnapshot(worldId = WorldId(1), state = TurnWorldState(1, 200, 1, 3600,
                Instant.parse("0200-01-01T00:00:00Z"), currentPhase = 1)),
            lastCommittedEventOrdinal = { turn -> bootstrapped += turn; 4 },
        )
        val firstKey = EventKey.derive("fixture", "first")
        val first = world.recordEvent(EventKind.PERSONAL_APPLIED, AudienceTarget.Self(1), firstKey)
        assertEquals(5, first.occurredAt.ordinal)
        assertEquals(first, world.recordEvent(EventKind.PERSONAL_APPLIED, AudienceTarget.Self(1), firstKey))
        assertEquals(listOf(first), world.consumeDirtyState().gameEvents)
        assertTrue(world.consumeDirtyState().gameEvents.isEmpty())
        assertEquals(first, world.recordEvent(EventKind.PERSONAL_APPLIED, AudienceTarget.Self(1), firstKey))
        assertEquals(listOf(first), world.consumeDirtyState().gameEvents,
            "a replay after a failed drain must restore the buffered event")

        val second = world.recordEvent(EventKind.PERSONAL_APPLIED, AudienceTarget.Self(1),
            EventKey.derive("fixture", "second"))
        assertEquals(6, second.occurredAt.ordinal)
        assertEquals(listOf(second), world.consumeDirtyState().gameEvents)
        assertEquals(listOf(EventTurn(200, 1, 1)), bootstrapped)

        world.setCurrentDate(200, 1, 2)
        val nextTurn = world.recordEvent(EventKind.PERSONAL_APPLIED, AudienceTarget.Self(1),
            EventKey.derive("fixture", "third"))
        assertEquals(0, nextTurn.occurredAt.ordinal)
    }
}
