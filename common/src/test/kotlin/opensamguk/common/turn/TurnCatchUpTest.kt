package opensamguk.common.turn

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TurnCatchUpTest {
    private val now = Instant.parse("2026-09-27T00:00:00Z")
    private val next = now.minus(Duration.ofHours(20))

    @Test
    fun `five minute tick and twenty hour backlog recover in twenty hours at double speed`() {
        val plan = TurnCatchUp.start(next, now)
        assertTrue(TurnCatchUp.shouldStart(next, 300, now))
        assertEquals(72_000L, plan.snapshot(next, now).remainingSeconds)
        assertEquals(now.plus(Duration.ofHours(20)).toString(), plan.snapshot(next, now).etaAt)
        assertEquals(now.plusSeconds(150), plan.wallTimeFor(next.plusSeconds(300)))
    }

    @Test
    fun `switching to quadruple speed changes ETA immediately without moving game deadlines`() {
        val switched = TurnCatchUp.start(next, now).switchMultiplier(4, now)
        assertEquals(24_000L, switched.snapshot(next, now).remainingSeconds)
        assertEquals(now.plusSeconds(24_000).toString(), switched.snapshot(next, now).etaAt)
        assertEquals(now.plusSeconds(75), switched.wallTimeFor(next.plusSeconds(300)))

        val later = now.plusSeconds(100)
        val backToDouble = switched.switchMultiplier(2, later)
        assertEquals(switched.virtualTime(later), backToDouble.virtualTime(later))
        assertEquals(later, backToDouble.lastCalculatedAt)

        val afterOneHour = now.plusSeconds(3600)
        val advancedWorld = next.plusSeconds(4 * 3600)
        val changedAfterProgress = switched.switchMultiplier(2, afterOneHour)
        assertEquals(17 * 3600L, changedAfterProgress.snapshot(advancedWorld, afterOneHour).remainingSeconds)
        assertEquals(afterOneHour.plusSeconds(17 * 3600L).toString(),
            changedAfterProgress.snapshot(advancedWorld, afterOneHour).etaAt)
    }

    @Test
    fun `threshold is strict and serialized plan survives restart`() {
        assertFalse(TurnCatchUp.shouldStart(now.minusSeconds(600), 300, now))
        assertTrue(TurnCatchUp.shouldStart(now.minusSeconds(601), 300, now))
        val plan = TurnCatchUp.start(next, now)
        assertEquals(plan, TurnCatchUp.fromMeta(plan.toMeta()))
        assertFailsWith<IllegalArgumentException> { plan.switchMultiplier(3, now) }
    }
}
