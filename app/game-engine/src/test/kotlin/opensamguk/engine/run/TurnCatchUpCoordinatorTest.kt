package opensamguk.engine.run

import java.time.Instant
import opensamguk.common.turn.TurnCatchUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnCatchUpCoordinatorTest {
    @Test
    fun `startup resumes persisted speed and a short restart preserves its next deadline`() {
        val start = Instant.parse("2026-09-27T00:00:00Z")
        var nextWorld = start.minusSeconds(72000)
        var stored: TurnCatchUp? = null
        val writes = mutableListOf<Pair<TurnCatchUp, Boolean>>()
        val coordinator = TurnCatchUpCoordinator(
            { nextWorld }, { 300 }, { stored }, { plan, done -> stored = plan; writes += plan to done },
        )
        coordinator.ensure(start, reanchor = true)
        assertEquals(2, stored?.multiplier)
        nextWorld = nextWorld.plusSeconds(300)
        val beforeRestart = checkNotNull(stored).wallTimeFor(nextWorld)
        coordinator.ensure(start.plusSeconds(20), reanchor = true)
        assertEquals(beforeRestart, stored?.wallTimeFor(nextWorld))
        assertEquals(2, writes.size)
    }

    @Test
    fun `switch and completion persist state and one completion intent`() {
        val start = Instant.parse("2026-09-27T00:00:00Z")
        var nextWorld = start.minusSeconds(72000)
        var stored: TurnCatchUp? = null
        val completed = mutableListOf<Boolean>()
        val coordinator = TurnCatchUpCoordinator(
            { nextWorld }, { 300 }, { stored }, { plan, done -> stored = plan; completed += done },
        )
        coordinator.ensure(start, reanchor = true)
        assertEquals(24_000L, coordinator.switchMultiplier(4, start).remainingSeconds)
        assertEquals(4, stored?.multiplier)
        nextWorld = start
        coordinator.finishIfCurrent(start)
        assertFalse(checkNotNull(stored).active)
        assertEquals(listOf(false, false, true), completed)
        coordinator.finishIfCurrent(start)
        assertEquals(3, completed.size)
        assertTrue(coordinator.snapshot(start).etaAt == null)
    }
}
