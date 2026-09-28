package opensamguk.engine.campaign

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKind

class OfflineDelegationTransitionTest {
    @Test
    fun `start and return emit one private event each even when a phase retries`() {
        val world = InMemoryTurnWorld(WorldSnapshot(
            worldId = WorldId(7),
            state = TurnWorldState(7, 190, 1, 3600, Instant.EPOCH, currentPhase = 1,
                config = mapOf("ruleProfile" to "HWIHA")),
            generals = listOf(TurnGeneral(12, "19", "player", 1, 1, 0,
                GeneralStats(70, 70, 70, 70, 70), 0, 0, 1, turnTime = Instant.EPOCH)),
        ))
        val transition = OfflineDelegationTransition(world, ChangeRecorder())
        val phase = DelegationPhase(190, 1, 1)

        transition.update(12, 19, false, phase)
        assertTrue(world.consumeDirtyState().gameEvents.isEmpty())
        transition.update(12, 19, true, phase)
        transition.update(12, 19, true, phase)
        val started = world.consumeDirtyState().gameEvents.single()
        assertEquals(EventKind.OFFLINE_DELEGATION_STARTED, started.kind)
        assertEquals(AudienceTarget.Self(12), started.audience)
        assertTrue(started.refs.isEmpty())
        assertTrue(started.facts.isEmpty())

        transition.update(12, 19, false, phase)
        transition.update(12, 19, false, phase)
        assertEquals(EventKind.OFFLINE_DELEGATION_ENDED, world.consumeDirtyState().gameEvents.single().kind)
        transition.update(12, 20, true, phase)
        assertTrue(world.consumeDirtyState().gameEvents.isEmpty())
    }
}
